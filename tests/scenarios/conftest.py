"""pytest-bdd wiring shared by every scenario area (spec-kitty-events#197, #276).

Gherkin tags are contract metadata, checked by ``scripts/ci/scenario_contract.py`` against the
closed vocabulary in ``tests/scenarios/README.md``. They are not turned into pytest marks, so
no ``PytestUnknownMarkWarning`` can arise from them; ``tests/test_scenario_contract.py`` runs
this tree under ``-W error::pytest.PytestUnknownMarkWarning`` to keep it that way. Two tags
have a runtime effect:

* ``@wip``: a draft scenario runs as a non-strict ``xfail``, so it is reported but never fails
  the run.
* ``@manual``: a specified promise this repo's CI cannot run. pytest-bdd hands the hook one tag
  at a time, so the skip is applied on the ``@manual-reason:<text>`` tag, which carries the
  reason; the contract check guarantees every ``@manual`` scenario has exactly that tag.
"""

from __future__ import annotations

from collections.abc import Callable

import pytest

MANUAL_REASON_PREFIX = "manual-reason:"


def pytest_bdd_apply_tag(tag: str, function: Callable[..., object]) -> bool:
    if tag == "wip":
        pytest.mark.xfail(reason="@wip draft scenario", strict=False)(function)
    elif tag.startswith(MANUAL_REASON_PREFIX):
        reason = tag[len(MANUAL_REASON_PREFIX) :]
        pytest.mark.skip(reason=f"@manual: {reason}")(function)
    return True
