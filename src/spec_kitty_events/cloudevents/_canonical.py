"""Canonical serialization of CloudEvents rows (the bytes the goldens pin).

UTF-8 JSON, object keys sorted by code point, ``,`` and ``:`` separators with no
whitespace, non-ASCII characters written as they are, integers as written and floats
in Python's shortest round-trip ``repr`` form. NaN and infinities are refused. Every
binding reproduces these bytes exactly; the Java binding's writer follows the same
rules.
"""

from __future__ import annotations

import json
from typing import Any

__all__ = ["canonical_bytes", "canonical_text"]


def canonical_text(value: Any) -> str:
    """Return the canonical JSON text of a JSON value.

    Raises:
        TypeError: If the value holds something JSON cannot carry.
        ValueError: If the value holds NaN or an infinity.
    """
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    )


def canonical_bytes(value: Any) -> bytes:
    """Return the canonical UTF-8 bytes of a JSON value (see :func:`canonical_text`)."""
    return canonical_text(value).encode("utf-8")
