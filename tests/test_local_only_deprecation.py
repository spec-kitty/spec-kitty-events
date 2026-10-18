"""``LOCAL_ONLY_EVENT_TYPES`` stays importable, empty and silent in 10.x (#118)."""

from __future__ import annotations

import ast
import inspect
import subprocess
import sys

from spec_kitty_events import LOCAL_ONLY_EVENT_TYPES, persistence


def test_core_import_is_warning_free_under_w_error() -> None:
    # Core imports the name unguarded; a deprecation warning would raise there.
    code = "from spec_kitty_events import LOCAL_ONLY_EVENT_TYPES as s; assert s == frozenset()"
    subprocess.run([sys.executable, "-W", "error", "-c", code], check=True)


def test_registry_stays_empty() -> None:
    assert LOCAL_ONLY_EVENT_TYPES == frozenset()


def test_append_event_does_not_consult_the_retired_registry() -> None:
    tree = ast.parse(inspect.getsource(persistence))
    names = {n.id for n in ast.walk(tree) if isinstance(n, ast.Name)}
    names |= {a.name for n in ast.walk(tree) if isinstance(n, ast.ImportFrom) for a in n.names}
    assert "LOCAL_ONLY_EVENT_TYPES" not in names
