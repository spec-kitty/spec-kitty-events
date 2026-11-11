"""Static checks of the release-protection audit workflow (#239, WP02; #221).

The workflow runs WP01's read-only audit script daily. These tests pin its shape: the trigger set,
the read-only token, the SHA-pinned actions, the script invocation, the timeout and (when installed)
actionlint. Each guard is a checker that the real file must pass and that a mutated copy must fail,
so a checker that is accidentally vacuous is caught.
"""

from __future__ import annotations

import copy
import re
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / ".github" / "workflows" / "release-protection-audit.yml"
SCRIPT = ROOT / "scripts" / "release" / "release_protection_audit.py"
EXPECTED = ROOT / "scripts" / "release" / "release-protection-expected.json"

SHA_PIN = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")


def _load(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def _on(workflow: dict) -> dict:
    # PyYAML (YAML 1.1) reads the bare key `on` as True.
    return workflow.get("on", workflow.get(True))


def _uses(node: object) -> list[str]:
    found: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "uses" and isinstance(value, str):
                found.append(value)
            else:
                found.extend(_uses(value))
    elif isinstance(node, list):
        for item in node:
            found.extend(_uses(item))
    return found


def _run_lines(node: object) -> list[str]:
    found: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "run" and isinstance(value, str):
                found.append(value)
            else:
                found.extend(_run_lines(value))
    elif isinstance(node, list):
        for item in node:
            found.extend(_run_lines(item))
    return found


# --- guard checkers: each raises AssertionError on a violation -------------------------------


def check_triggers(workflow: dict) -> None:
    assert set(_on(workflow)) == {"schedule", "workflow_dispatch"}


def check_workflow_token_read_only(workflow: dict) -> None:
    assert workflow["permissions"] == {"contents": "read"}


def check_no_job_overrides_permissions(workflow: dict) -> None:
    # A job-level permissions block replaces the workflow default and could widen the token.
    for name, job in workflow["jobs"].items():
        assert "permissions" not in job, f"job {name} declares its own permissions"


def check_every_action_sha_pinned(workflow: dict) -> None:
    uses = _uses(workflow["jobs"])
    assert uses, "no uses: found; the positive control is broken"
    for ref in uses:
        assert SHA_PIN.match(ref), f"{ref} is not pinned to a 40-hex commit SHA"


def check_script_invoked_with_expected_file(workflow: dict) -> None:
    runs = [r for r in _run_lines(workflow["jobs"]) if "release_protection_audit.py" in r]
    assert runs, "no step runs the audit script"
    assert any(
        "scripts/release/release_protection_audit.py" in r
        and "--expected scripts/release/release-protection-expected.json" in r
        for r in runs
    ), runs


def check_timeout_bounded(workflow: dict) -> None:
    for name, job in workflow["jobs"].items():
        assert job["timeout-minutes"] <= 30, f"job {name} may run longer than 30 minutes"


CHECKERS = [
    check_triggers,
    check_workflow_token_read_only,
    check_no_job_overrides_permissions,
    check_every_action_sha_pinned,
    check_script_invoked_with_expected_file,
    check_timeout_bounded,
]


# --- tests -----------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def workflow() -> dict:
    return _load(AUDIT)


@pytest.mark.parametrize("checker", CHECKERS, ids=lambda c: c.__name__)
def test_real_workflow_passes_every_guard(workflow: dict, checker) -> None:
    checker(copy.deepcopy(workflow))


def test_audit_runs_only_on_schedule_and_dispatch(workflow: dict) -> None:
    assert set(_on(workflow)) == {"schedule", "workflow_dispatch"}


def test_audit_token_is_contents_read_only(workflow: dict) -> None:
    check_workflow_token_read_only(workflow)
    check_no_job_overrides_permissions(workflow)


def test_every_audit_action_is_sha_pinned(workflow: dict) -> None:
    check_every_action_sha_pinned(workflow)
    for line in AUDIT.read_text(encoding="utf-8").splitlines():
        if re.search(r"uses: [^.\s][^@\s]*@", line):
            assert re.search(r"@[0-9a-f]{40} # v\d+\.\d+\.\d+$", line), line


def test_audit_runs_the_audit_script_with_the_expected_file(workflow: dict) -> None:
    check_script_invoked_with_expected_file(workflow)
    assert SCRIPT.is_file()
    assert EXPECTED.is_file()


def test_audit_timeout_bounds_the_run(workflow: dict) -> None:
    check_timeout_bounded(workflow)


def _mutant(workflow: dict, mutate) -> dict:
    mutant = copy.deepcopy(workflow)
    mutate(mutant)
    return mutant


def _add_push_trigger(wf: dict) -> None:
    _on(wf)["push"] = {"branches": ["main"]}


def _widen_workflow_token(wf: dict) -> None:
    wf["permissions"] = {"contents": "write"}


def _widen_job_token(wf: dict) -> None:
    wf["jobs"]["audit"]["permissions"] = {"contents": "write"}


def _unpin_checkout(wf: dict) -> None:
    steps = wf["jobs"]["audit"]["steps"]
    steps[0]["uses"] = "actions/checkout@v5"


MUTANTS = [
    (check_triggers, _add_push_trigger),
    (check_workflow_token_read_only, _widen_workflow_token),
    (check_no_job_overrides_permissions, _widen_job_token),
    (check_every_action_sha_pinned, _unpin_checkout),
]


@pytest.mark.parametrize(
    ("checker", "mutate"),
    MUTANTS,
    ids=[f"{c.__name__}-{m.__name__}" for c, m in MUTANTS],
)
def test_each_guard_fails_on_its_mutant(workflow: dict, checker, mutate) -> None:
    mutant = _mutant(workflow, mutate)
    with pytest.raises(AssertionError):
        checker(mutant)


@pytest.mark.skipif(shutil.which("actionlint") is None, reason="actionlint is not installed")
def test_actionlint_accepts_the_audit_workflow() -> None:
    subprocess.run(["actionlint", str(AUDIT)], check=True)
