"""Credential lifetime and least-privilege boundaries for the private harness checkout."""

import json
import os
import re
import subprocess
from pathlib import Path
import pytest
import yaml

FILE = Path(__file__).resolve().parents[1] / ".github/workflows/cross-repo-harness-tests.yml"


@pytest.fixture
def config():
    return yaml.safe_load(FILE.read_text())["jobs"]["cross-repo-harness-tests"]


def test_job_token_cannot_write(config):
    assert config["permissions"] == {"contents": "read"}


def test_exact_canonical_target_pin_and_no_persisted_credentials(config):
    checks = [s for s in config["steps"] if s.get("uses", "").startswith("actions/checkout@")]
    assert len(checks) == 2
    assert all(s["with"].get("persist-credentials") is False for s in checks)
    harness = checks[1]["with"]
    assert harness["repository"] == "spec-kitty/EXPERIMENTAL-spec-kitty-end-to-end-testing"
    assert harness["ref"] == "193f5c0eb07c8fdb6943d91d205ab892b98d6dfc"
    assert "ssh-key" not in harness
    assert harness["token"] == "${{ steps.harness-token.outputs.token }}"


def test_mint_uses_verified_action_and_explicit_attenuation(config):
    mint = next(s for s in config["steps"] if s.get("id") == "harness-token")
    assert (
        mint["uses"] == "actions/create-github-app-token@fee1f7d63c2ff003460e3d139729b119787bc349"
    )
    assert mint["with"] == {
        "app-id": "${{ secrets.SK_CI_APP_ID }}",
        "private-key": "${{ secrets.SK_CI_APP_PRIVATE_KEY }}",
        "owner": "spec-kitty",
        "repositories": "EXPERIMENTAL-spec-kitty-end-to-end-testing",
        "permission-contents": "read",
    }
    assert not mint.get("continue-on-error", False)


def test_revoke_precedes_all_source_execution_and_has_failure_cleanup(config):
    steps = config["steps"]
    cleanup = next(s for s in steps if s["name"].startswith("Revoke harness"))
    assert cleanup["if"] == "always() && steps.harness-token.outcome == 'success'"
    assert not cleanup.get("continue-on-error", False)
    install = next(s for s in steps if s["name"] == "Install harness dependencies (frozen)")
    assert steps.index(cleanup) < steps.index(install)
    for s in steps[steps.index(cleanup) + 1 :]:
        assert "GH_TOKEN" not in s.get("env", {})
        assert "harness-token.outputs.token" not in json.dumps(s)


@pytest.mark.parametrize("transport_exit", [0, 1])
def test_actual_revoke_shell_propagates_failure(config, tmp_path, transport_exit):
    cleanup = next(s for s in config["steps"] if s["name"].startswith("Revoke harness"))
    gh = tmp_path / "gh"
    log = tmp_path / "calls"
    gh.write_text(f'#!/bin/sh\nprintf "%s\\n" "$*" > "{log}"\nexit {transport_exit}\n')
    gh.chmod(0o755)
    r = subprocess.run(
        ["bash", "-e", "-c", cleanup["run"]],
        env={"PATH": str(tmp_path) + ":" + os.environ["PATH"]},
        capture_output=True,
        text=True,
    )
    assert r.returncode == transport_exit
    assert log.read_text().strip() == "api --method DELETE /installation/token"


def test_no_source_execution_while_harness_token_is_live(config):
    steps = config["steps"]
    cleanup_index = next(
        i for i, step in enumerate(steps) if step["name"].startswith("Revoke harness")
    )
    for step in steps[:cleanup_index]:
        assert "run" not in step
        assert step.get("uses", "").startswith(
            ("actions/checkout@", "actions/create-github-app-token@")
        )
        assert "if" not in step
        assert not step.get("continue-on-error", False)
    install = next(
        step for step in steps if step["name"] == "Install harness dependencies (frozen)"
    )
    assert "if" not in install  # GitHub's default success() refuses checkout/revocation failures.


def test_no_privileged_pr_trigger_or_job_credential_exposure(config):
    # BaseLoader preserves YAML's `on` key rather than interpreting it as a boolean.
    workflow = yaml.load(FILE.read_text(), Loader=yaml.BaseLoader)
    assert set(workflow["on"]) == {"push", "pull_request"}
    assert "env" not in workflow
    assert "env" not in config
    assert "continue-on-error" not in config


# --- fork pull requests (#95) ------------------------------------------------------------
#
# GitHub withholds repository secrets from a `pull_request` run whose head is a fork, so the
# gate cannot mint its App token there. Operator ruling (Q1, option a): on a fork PR the gate
# job is skipped (GitHub reports a job skipped by `if:` as success to a required check) and
# `fork-pr-harness-deferred` says so; a maintainer runs the harness on an upstream branch.
# The tests below evaluate the workflow's own `if:` expressions, not a copy of them.

REPO = "spec-kitty/spec-kitty-events"
NOTICE_JOB = "fork-pr-harness-deferred"
_TOKEN = re.compile(r"\s*(\(|\)|&&|\|\||!=|==|!|,|'[^']*'|[A-Za-z_][\w.\-*]*)")


def _evaluate(expression: str, context: dict) -> object:
    """Evaluate the subset of GitHub's expression syntax these `if:`s use.

    Modelled semantics: `==`, `!=` and `contains` ignore case; a missing property is null; a
    null never equals a string; the `*` object filter of a missing value is an empty array.
    """
    tokens, pos = [], 0
    expression = expression.strip()
    while pos < len(expression):
        match = _TOKEN.match(expression, pos)
        assert match, f"unsupported syntax at {expression[pos:]!r}"
        tokens.append(match.group(1))
        pos = match.end()
        while pos < len(expression) and expression[pos].isspace():
            pos += 1

    def lookup(path: str) -> object:
        value: object = context
        for part in path.split("."):
            if part == "*":  # GitHub's object filter; a missing value filters to nothing
                if not isinstance(value, list):
                    return []
                rest = path.split(".*.", 1)[1]
                return [item.get(rest) for item in value if isinstance(item, dict)]
            value = value.get(part) if isinstance(value, dict) else None
        return value

    def parse_or(i: int) -> tuple[object, int]:
        left, i = parse_and(i)
        while i < len(tokens) and tokens[i] == "||":
            right, i = parse_and(i + 1)
            left = left or right
        return left, i

    def parse_and(i: int) -> tuple[object, int]:
        left, i = parse_cmp(i)
        while i < len(tokens) and tokens[i] == "&&":
            right, i = parse_cmp(i + 1)
            left = left and right
        return left, i

    def parse_cmp(i: int) -> tuple[object, int]:
        left, i = parse_unary(i)
        if i < len(tokens) and tokens[i] in ("==", "!="):
            op = tokens[i]
            right, i = parse_unary(i + 1)
            left, right = _fold(left), _fold(right)
            left = (left == right) if op == "==" else (left != right)
        return left, i

    def parse_unary(i: int) -> tuple[object, int]:
        token = tokens[i]
        if token == "!":
            value, i = parse_unary(i + 1)
            return not value, i
        if token == "(":
            value, i = parse_or(i + 1)
            assert tokens[i] == ")"
            return value, i + 1
        if token.startswith("'"):
            return token[1:-1], i + 1
        if token == "contains":
            assert tokens[i + 1] == "("
            haystack, i = parse_or(i + 2)
            assert tokens[i] == ","
            needle, i = parse_or(i + 1)
            assert tokens[i] == ")"
            return _fold(needle) in [_fold(item) for item in haystack or []], i + 1
        return lookup(token), i + 1

    value, end = parse_or(0)
    assert end == len(tokens), f"trailing tokens {tokens[end:]}"
    return value


def _fold(value: object) -> object:
    return value.lower() if isinstance(value, str) else value


def _context(event: str, head_repo: str | None = REPO, labels: tuple[str, ...] = ()) -> dict:
    github: dict = {"event_name": event, "repository": REPO, "event": {}}
    if event == "pull_request":
        head = {"sha": "a" * 40, "repo": None if head_repo is None else {"full_name": head_repo}}
        github["event"] = {
            "pull_request": {"head": head, "labels": [{"name": name} for name in labels]}
        }
    return {"github": github}


def _runs(job: dict, context: dict) -> bool:
    return bool(_evaluate(job["if"], context))


@pytest.fixture
def jobs():
    return yaml.load(FILE.read_text(), Loader=yaml.BaseLoader)["jobs"]


def test_the_evaluator_reads_the_expressions_it_is_given():
    ctx = _context("pull_request", labels=("x",))
    assert _evaluate("contains(github.event.pull_request.labels.*.name, 'x')", ctx) is True
    assert _evaluate("!(github.event_name == 'push') && 'a' != 'b'", ctx) is True
    assert _evaluate(
        "'ABC' == 'abc' && contains(github.event.pull_request.labels.*.name, 'X')", ctx
    )
    with pytest.raises(AssertionError):
        _evaluate("github.event_name == 'push' ? 1", ctx)


CASES = {
    # case: (context, gate runs, notice runs)
    "push to main": (_context("push"), True, False),
    "upstream PR": (_context("pull_request"), True, False),
    "fork PR": (_context("pull_request", "contributor/spec-kitty-events"), False, True),
    "fork PR from a deleted fork": (_context("pull_request", None), False, True),
    "upstream PR, name in another case": (
        _context("pull_request", "Spec-Kitty/Spec-Kitty-Events"),
        True,
        False,
    ),
    "fork PR whose owner mimics ours": (
        _context("pull_request", "spec-kitty-evil/spec-kitty-events"),
        False,
        True,
    ),
    "paused upstream PR": (_context("pull_request", labels=("pr:pause-ci",)), False, False),
    "paused fork PR": (
        _context("pull_request", "contributor/spec-kitty-events", ("pr:pause-ci",)),
        False,
        False,
    ),
}


@pytest.mark.parametrize("case", CASES)
def test_each_event_runs_exactly_the_expected_job(jobs, case):
    context, gate, notice = CASES[case]
    assert _runs(jobs["cross-repo-harness-tests"], context) is gate
    assert _runs(jobs[NOTICE_JOB], context) is notice


def test_the_required_check_name_stays_on_the_gate_and_the_notice_cannot_shadow_it(jobs):
    assert jobs["cross-repo-harness-tests"]["name"] == "cross-repo-harness-tests"
    assert jobs[NOTICE_JOB]["name"] != "cross-repo-harness-tests"
    assert set(jobs) == {"cross-repo-harness-tests", NOTICE_JOB}


def test_fork_notice_job_runs_no_pr_code_and_holds_no_credential(jobs):
    notice = jobs[NOTICE_JOB]
    assert notice["permissions"] == {}
    text = json.dumps(notice)
    assert "secrets." not in text and "github.token" not in text and "GH_TOKEN" not in text
    assert "needs" not in notice and "continue-on-error" not in notice
    for step in notice["steps"]:
        assert "uses" not in step  # no checkout, no third-party action
        assert "${{" not in step["run"]  # event values only through env


def test_fork_notice_script_reports_the_deferral_and_succeeds(jobs, tmp_path):
    step = jobs[NOTICE_JOB]["steps"][0]
    summary = tmp_path / "summary"
    result = subprocess.run(
        ["bash", "-e", "-c", step["run"]],
        env={
            "PATH": os.environ["PATH"],
            "GITHUB_STEP_SUMMARY": str(summary),
            "HEAD_REPO": "contributor/spec-kitty-events",
            "HEAD_SHA": "b" * 40,
        },
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout.startswith("::notice title=cross-repo-harness-tests deferred::")
    written = summary.read_text()
    assert "b" * 40 in written and "`contributor/spec-kitty-events`" in written
    assert "Pull requests from forks" in written
