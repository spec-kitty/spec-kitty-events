"""pytest-bdd wiring shared by every scenario area (spec-kitty-events#197).

Gherkin tags are contract metadata, checked by ``scripts/ci/scenario_contract.py`` against the
closed vocabulary in ``tests/scenarios/README.md``. They are not turned into pytest marks, so
no ``PytestUnknownMarkWarning`` can arise from them; ``tests/test_scenario_contract.py`` runs
this tree under ``-W error::pytest.PytestUnknownMarkWarning`` to keep it that way. The one tag
with a runtime effect is ``@wip``: a draft scenario runs as a non-strict ``xfail``, so it is
reported but never fails the run.
"""

from __future__ import annotations

from collections.abc import Callable

import pytest


def pytest_bdd_apply_tag(tag: str, function: Callable[..., object]) -> bool:
    if tag == "wip":
        pytest.mark.xfail(reason="@wip draft scenario", strict=False)(function)
    return True
