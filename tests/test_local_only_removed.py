"""``LOCAL_ONLY_EVENT_TYPES`` is removed in 11.0.0 (FR-010, #118 deprecation)."""

from __future__ import annotations

import ast
import inspect

import pytest

import spec_kitty_events
from spec_kitty_events import persistence


def test_name_is_not_importable() -> None:
    with pytest.raises(ImportError):
        from spec_kitty_events import LOCAL_ONLY_EVENT_TYPES  # noqa: F401


def test_name_absent_from_all_and_namespace() -> None:
    assert "LOCAL_ONLY_EVENT_TYPES" not in spec_kitty_events.__all__
    assert not hasattr(spec_kitty_events, "LOCAL_ONLY_EVENT_TYPES")


def test_persistence_does_not_mention_the_registry() -> None:
    tree = ast.parse(inspect.getsource(persistence))
    names = {n.id for n in ast.walk(tree) if isinstance(n, ast.Name)}
    assert "LOCAL_ONLY_EVENT_TYPES" not in names
    assert "LOCAL_ONLY" not in (persistence.__doc__ or "")
