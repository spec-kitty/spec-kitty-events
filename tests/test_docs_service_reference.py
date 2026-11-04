"""The service contracts publish as unreleased OpenAPI reference pages (issue #194, WP03).

``site/astro.config.mjs`` declares both service bundles with ``released: false`` (the flip
rule for the first final ``service-v*`` tag lives in ``docs/api/services.md``),
``site/package.json`` exact-pins the toolkit's OpenAPI renderer peers, the API section index
links the services hub, and the fail-closed staging script reads both bundles from
``SKE_CONTRACT_BUNDLES_DIR``. Coarse textual pins only; the end-to-end proof is the workflow
(the published-digests step) and a local build with bundles produced.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent
_ASTRO_CONFIG = _REPO_ROOT / "site" / "astro.config.mjs"
_PACKAGE_JSON = _REPO_ROOT / "site" / "package.json"
_STAGING_SCRIPT = _REPO_ROOT / "site" / "scripts" / "stage-docs-inputs.mjs"
_API_README = _REPO_ROOT / "docs" / "api" / "README.md"
_SERVICES_MD = _REPO_ROOT / "docs" / "api" / "services.md"
_REQUIREMENTS = _REPO_ROOT / "scripts" / "docs" / "requirements.txt"

_MODULES = ("mission-status", "mission-write")


def _spec_pattern(module: str) -> "re.Pattern[str]":
    return re.compile(
        r"\{\s*path:\s*'openapi/"
        + module
        + r"\.yaml',\s*label:\s*'"
        + module
        + r"',\s*released:\s*false\s*\}"
    )


def test_astro_config_declares_both_service_specs_unreleased() -> None:
    text = _ASTRO_CONFIG.read_text(encoding="utf-8")
    assert "openapi:" in text
    for module in _MODULES:
        assert _spec_pattern(module).search(text), module


def test_package_json_exact_pins_the_openapi_renderer_peers() -> None:
    # The doc-toolkit 0.6.0 peer contract for the openapi option; do not widen these ranges.
    dependencies = json.loads(_PACKAGE_JSON.read_text(encoding="utf-8"))["dependencies"]
    assert dependencies["starlight-openapi"] == "0.27.0"
    assert dependencies["@astrojs/markdown-satteri"] == "0.4.4"


def test_api_readme_links_the_services_hub() -> None:
    # House link convention: root-absolute routes, not file links (docsite.md).
    assert "(/api/services/)" in _API_README.read_text(encoding="utf-8")


def test_services_hub_exists_with_the_required_frontmatter() -> None:
    text = _SERVICES_MD.read_text(encoding="utf-8")
    frontmatter = re.fullmatch(r"---\n(.*?)\n---\n.*", text, flags=re.DOTALL)
    assert frontmatter, "services.md carries no frontmatter block"
    fields = dict(re.findall(r"^(\w+):\s*(.+?)\s*$", frontmatter.group(1), flags=re.MULTILINE))
    assert fields["title"] == "Service APIs"
    assert fields["doc_status"] == "active"
    assert fields["type"] == "API"
    description = fields["description"].strip("'\"")
    assert len(description) <= 180
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}T00:00:00Z", fields["updated"])


def test_staging_script_names_both_bundles_and_stays_fail_closed() -> None:
    text = _STAGING_SCRIPT.read_text(encoding="utf-8")
    assert "SKE_CONTRACT_BUNDLES_DIR" in text
    assert "bundle-out" in text
    for module in _MODULES:
        assert module in text
    # Missing bundle -> clear error and a non-zero exit.
    assert "process.exit(1)" in text
    assert "bundle.py" in text


def test_the_docs_python_requirements_pin_pyyaml_to_uv_lock() -> None:
    assert "pyyaml==6.0.3" in _REQUIREMENTS.read_text(encoding="utf-8")
