"""Producer row schemas: per-row-kind ``data`` schemas, the registry and composed schemas.

This package holds the machine-readable contract that a Spec Kitty event producer
(Java, Python or any other language) writes against for CloudEvents 1.0 structured-JSON
rows (spec-kitty/spec-kitty#6185, spec-kitty-events#92). The committed JSON files are the
source of truth:

* ``registry.json``: one entry per type (type string, ``dataschema`` URN, schema file,
  scope, ``subject`` rule, required attributes, CLI legacy form, lifted members, and the
  ``transport`` mapping from the 3.0.0 envelope).
* ``data/<name>.schema.json``: the JSON Schema (2020-12) of one type's ``data`` member.
  Self-contained: no ``$ref`` and no ``$defs``.
* ``envelope.schema.json``: the write profile, for rows a producer writes.
* ``envelope.read.schema.json``: the read profile, for rows a reader meets, including the
  upcast form of legacy rows (no clock required, legacy UUID ids, transport extensions).

The functions below load those files and compose two further schemas from them, so that
nothing is authored twice: :func:`row_schema` (a whole CloudEvents line of one type, in
either profile) and :func:`legacy_row_form_schema` (the persisted CLI legacy line of one
type, which is the data schema plus the registry's lifted members).

Status: non-normative until the cutover release of ADR 0001. Nothing here changes what
``validate_strict_envelope`` accepts. Validation against these schemas needs the optional
``jsonschema`` dependency (the ``conformance`` extra); loading them does not.
"""

from __future__ import annotations

import copy
import json
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, List, Tuple

__all__ = [
    "PROFILES",
    "RegistryError",
    "data_schema",
    "envelope_schema",
    "get_entry",
    "legacy_row_form_schema",
    "load_registry",
    "registered_types",
    "row_schema",
]

_ROOT = Path(__file__).parent
_MISSION_SOURCE = "^/spec-kitty/missions/"
_PROJECT_SOURCE = "^/spec-kitty/projects/"
_META_KEYS = ("$schema", "$id", "title")
PROFILES = ("write", "read")


class RegistryError(LookupError):
    """Raised for an unregistered type or a malformed registry entry."""


def _read_json(path: Path) -> Dict[str, Any]:
    loaded: Dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return loaded


@lru_cache(maxsize=1)
def _registry() -> Dict[str, Any]:
    return _read_json(_ROOT / "registry.json")


def load_registry() -> Dict[str, Any]:
    """Return a fresh copy of the parsed ``registry.json``."""
    return copy.deepcopy(_registry())


def registered_types() -> Tuple[str, ...]:
    """Return every registered type string, in registry order."""
    return tuple(entry["type"] for entry in _registry()["types"])


def get_entry(type_or_name: str) -> Dict[str, Any]:
    """Return a copy of the registry entry for a full type string or its short name.

    Raises:
        RegistryError: If nothing is registered under that name.
    """
    for entry in _registry()["types"]:
        if type_or_name in (entry["type"], entry["name"]):
            return copy.deepcopy(entry)
    raise RegistryError(f"type is not registered: {type_or_name!r}")


@lru_cache(maxsize=None)
def _data_schema(schema_file: str) -> Dict[str, Any]:
    return _read_json(_ROOT / schema_file)


def data_schema(type_or_name: str) -> Dict[str, Any]:
    """Return a copy of the ``data`` schema of a registered type."""
    return copy.deepcopy(_data_schema(get_entry(type_or_name)["schema"]))


def _check_profile(profile: str) -> None:
    if profile not in PROFILES:
        raise ValueError(f"profile must be one of {PROFILES}, not {profile!r}")


def envelope_schema(profile: str = "write") -> Dict[str, Any]:
    """Return a copy of the envelope schema of a profile (``write`` or ``read``)."""
    _check_profile(profile)
    return copy.deepcopy(_data_schema(_registry()["envelope_schemas"][profile]))


def _embeddable(schema: Dict[str, Any]) -> Dict[str, Any]:
    """Drop the document-level keys so a schema can sit inside another one."""
    return {k: v for k, v in schema.items() if k not in _META_KEYS}


def row_schema(type_or_name: str, profile: str = "write") -> Dict[str, Any]:
    """Compose the schema of a whole CloudEvents line of one registered type.

    The envelope schema of the profile with ``type`` and ``dataschema`` pinned to the entry, ``data``
    replaced by the type's data schema, the entry's ``required_attributes`` required, and
    the ``subject`` and ``missionid`` rules of its scope applied.
    """
    _check_profile(profile)
    registry = _registry()
    entry = get_entry(type_or_name)
    row = envelope_schema(profile)
    props: Dict[str, Any] = row["properties"]
    props["type"] = {"type": "string", "const": entry["type"]}
    props["dataschema"] = {"type": "string", "const": entry["dataschema"]}
    props["data"] = _embeddable(data_schema(entry["name"]))
    required: List[str] = list(row["required"])
    for attribute in entry["required_attributes"]:
        if attribute not in required:
            required.append(attribute)
    if entry["subject"] == "wp_id":
        required.append("subject")
    row["required"] = required
    suffix = ".row" if profile == "write" else ".row.read"
    row["title"] = f"{entry['type']} row" + ("" if profile == "write" else " (read profile)")
    row["$id"] = registry["dataschema_base"] + entry["name"] + suffix

    conditions: List[Dict[str, Any]] = []
    if entry["scope"] == "project":
        props["source"] = {"type": "string", "pattern": _PROJECT_SOURCE + "[^/\\s]+(?![\\s\\S])"}
        conditions.append({"not": {"required": ["subject"]}})
        conditions.append({"not": {"required": ["missionid"]}})
    else:
        conditions.append(
            {
                "if": {
                    "properties": {"source": {"pattern": _MISSION_SOURCE}},
                    "required": ["source"],
                },
                "then": {"required": ["missionid"]},
                "else": {"not": {"required": ["missionid"]}},
            }
        )
        if entry["subject"] == "none":
            conditions.append(
                {
                    "if": {
                        "properties": {"source": {"pattern": _MISSION_SOURCE}},
                        "required": ["source"],
                    },
                    "then": {"not": {"required": ["subject"]}},
                    # project-scoped fallback source: the subject is the aggregate
                    "else": {"required": ["subject"]},
                }
            )
    row["allOf"] = conditions
    return row


def legacy_row_form_schema(type_or_name: str) -> Dict[str, Any]:
    """Compose the schema of the persisted legacy line of one registered type.

    Generated from the type's data schema and the registry's lifted members, so there is
    no second authored schema (spec-kitty#6185 FR-032). For a ``flat`` shape (lane,
    annotation and retrospective rows) the lifted members and the shape's constants go
    back into the one object, and a data member the lifted actor replaces is removed.
    For a ``payload_envelope`` shape (lifecycle, decision and run-journal rows) the data
    schema becomes the ``payload`` member of the shape's envelope.

    Raises:
        RegistryError: If the type is unregistered or has no CLI legacy form (a type read
            from the 3.0.0 transport envelope only).
    """
    registry = _registry()
    entry = get_entry(type_or_name)
    legacy = entry["legacy"]
    if legacy is None:
        raise RegistryError(f"type has no CLI legacy form: {entry['type']!r}")
    data = _embeddable(data_schema(entry["name"]))
    shape = registry["legacy_shapes"][legacy["shape"]]
    meta: Dict[str, Any] = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": registry["dataschema_base"] + entry["name"] + ".legacy",
        "title": f"{entry['type']} legacy row form",
    }

    if shape["form"] == "flat":
        properties: Dict[str, Any] = copy.deepcopy(data["properties"])
        required: List[str] = list(data.get("required", []))
        for lifted in entry["lifted"]:
            binding = lifted.get("binding_member")
            if binding is not None:
                properties.pop(binding, None)
                required = [name for name in required if name != binding]
            properties[lifted["member"]] = lifted["legacy_schema"]
            if lifted["required"]:
                required.append(lifted["member"])
        for key, value in legacy["constants"].items():
            properties[key] = {"const": value}
            required.append(key)
        return {
            **meta,
            "type": "object",
            "properties": properties,
            "required": required,
            "additionalProperties": False,
        }

    members: Dict[str, Any] = copy.deepcopy(shape["members"])
    members[shape["discriminator_member"]] = {
        "type": "string",
        "const": legacy["discriminator_value"],
    }
    members[shape["payload_member"]] = data
    return {
        **meta,
        "type": "object",
        "properties": members,
        "required": list(shape["required"]),
        "additionalProperties": False,
    }
