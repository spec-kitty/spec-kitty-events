"""pytest-bdd wiring shared by every scenario area (spec-kitty-events#197, #276).

Gherkin tags are contract metadata, checked by ``scripts/ci/scenario_contract.py`` against the
closed vocabulary in ``tests/scenarios/README.md``. Scenario, Feature and Rule tags reach the
hook below and are not turned into pytest marks, so no ``PytestUnknownMarkWarning`` can arise
from them; ``tests/test_scenario_contract.py`` runs this tree under
``-W error::pytest.PytestUnknownMarkWarning`` to keep it that way. An ``Examples:`` block's tags
never reach the hook (pytest-bdd turns them into raw marks), so the contract check refuses the
ones below there (``WIP_ON_EXAMPLES``, ``MANUAL_ON_EXAMPLES``, ``JVM_ONLY_ON_EXAMPLES``). Three
tags have a runtime effect:

* ``@wip``: a draft scenario runs as a non-strict ``xfail``, so it is reported but never fails
  the run.
* ``@manual``: a specified promise this repo's CI cannot run. pytest-bdd hands the hook one tag
  at a time, in no fixed order, so the hook records ``@manual`` and ``@manual-reason:<text>`` on
  the scenario function and applies the skip once both are present. A reason without
  ``@manual``, or ``@manual`` without a reason, never skips; the contract check refuses both
  (``MANUAL_REASON_WITHOUT_MANUAL``, ``MANUAL_MISSING_REASON``).
* ``@jvm-only``: a promise only the JVM services implement, bound by Cucumber-JVM alone. pytest-bdd
  has no steps for it, so it is skipped here.
"""

from __future__ import annotations

from collections.abc import Callable

import pytest

MANUAL_REASON_PREFIX = "manual-reason:"
_MANUAL_SEEN = "_spec_kitty_manual"
_MANUAL_REASONS = "_spec_kitty_manual_reasons"
_MANUAL_SKIPPED = "_spec_kitty_manual_skipped"


def _apply_manual(tag: str, function: Callable[..., object]) -> None:
    if tag == "manual":
        setattr(function, _MANUAL_SEEN, True)
    else:
        reasons = [*getattr(function, _MANUAL_REASONS, []), tag[len(MANUAL_REASON_PREFIX) :]]
        setattr(function, _MANUAL_REASONS, reasons)
    reasons = getattr(function, _MANUAL_REASONS, [])
    if (
        getattr(function, _MANUAL_SEEN, False)
        and reasons
        and not hasattr(function, _MANUAL_SKIPPED)
    ):
        setattr(function, _MANUAL_SKIPPED, True)
        pytest.mark.skip(reason=f"@manual: {'; '.join(sorted(reasons))}")(function)


def pytest_bdd_apply_tag(tag: str, function: Callable[..., object]) -> bool:
    if tag == "wip":
        pytest.mark.xfail(reason="@wip draft scenario", strict=False)(function)
    elif tag == "manual" or tag.startswith(MANUAL_REASON_PREFIX):
        _apply_manual(tag, function)
    elif tag == "jvm-only":
        pytest.mark.skip(reason="@jvm-only: bound by Cucumber-JVM, not pytest-bdd")(function)
    return True
