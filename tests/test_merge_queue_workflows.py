"""Merge-queue readiness (issue #177, ADR 0005 D3).

Every workflow that produces a required check runs on ``merge_group``; every
``pull_request``-only expression has a ``merge_group`` branch; the path-filtered
gates (``service-gate``, the docs ``build``) always report and skip their heavy work
through an in-job diff instead; ``ci.yml`` never treats a queue SHA as ``main`` (its
verdict poster would refresh main's CI baseline) and fails a red queue run; and a
``pr:pause-ci`` label pauses a pull request's CI, as in core (spec-kitty/spec-kitty#6961).

The behavioural tests run the workflows' own ``run:`` scripts, extracted from the YAML,
so they pin what the runner executes, not a copy of it.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
CI = WORKFLOWS / "ci.yml"
ACCEPTANCE = WORKFLOWS / "acceptance.yml"
HARNESS = WORKFLOWS / "cross-repo-harness-tests.yml"
SERVICE = WORKFLOWS / "service.yml"
DOCS = WORKFLOWS / "docs-site.yml"
PAUSE = WORKFLOWS / "pr-pause-ci.yml"

REQUIRED_CHECK_WORKFLOWS = [CI, ACCEPTANCE, HARNESS, SERVICE, DOCS]
# The check names the operator makes required (ADR 0005 D3); pr-pause-ci.yml fails them.
REQUIRED_CHECKS = {"suite", "acceptance", "cross-repo-harness-tests", "service-gate", "docs-build"}
SHA_PIN = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
PIN_LINE = re.compile(r"uses: [^@\s]+@[0-9a-f]{40} # v\d+\.\d+\.\d+$")
PAUSE_GUARD = "!contains(github.event.pull_request.labels.*.name, 'pr:pause-ci')"
HAS_BASH = shutil.which("bash") is not None


def _load(path: Path) -> dict[str, Any]:
    # BaseLoader keeps `on` a string key (safe_load turns it into True).
    data: dict[str, Any] = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    return data


def _step(path: Path, job: str, name: str) -> dict[str, Any]:
    steps: list[dict[str, Any]] = _load(path)["jobs"][job]["steps"]
    return next(s for s in steps if s.get("name") == name)


def _run_step(
    step: dict[str, Any], env: dict[str, str], cwd: Path, tmp_path: Path
) -> dict[str, str]:
    """Run a step's script as the runner would (bash -e), return what it wrote to GITHUB_OUTPUT."""
    out = tmp_path / "github_output"
    out.write_text("", encoding="utf-8")
    script = tmp_path / "step.sh"
    script.write_text(step["run"], encoding="utf-8")
    full_env = {**os.environ, **env, "GITHUB_OUTPUT": str(out), "GITHUB_STEP_SUMMARY": os.devnull}
    result = subprocess.run(
        ["bash", "--noprofile", "--norc", "-e", str(script)],
        cwd=cwd,
        env=full_env,
        capture_output=True,
        text=True,
    )
    outputs = dict(
        line.split("=", 1) for line in out.read_text(encoding="utf-8").splitlines() if "=" in line
    )
    outputs["__rc__"] = str(result.returncode)
    outputs["__stderr__"] = result.stdout + result.stderr
    return outputs


# --- every required-check workflow runs on merge_group -------------------------------


@pytest.mark.parametrize("path", REQUIRED_CHECK_WORKFLOWS, ids=lambda p: p.name)
def test_required_check_workflows_run_on_merge_group(path: Path) -> None:
    on = _load(path)["on"]
    assert on["merge_group"] == {"types": ["checks_requested"]}


@pytest.mark.parametrize("path", [SERVICE, DOCS], ids=lambda p: p.name)
def test_gated_workflows_have_no_pull_request_path_filter(path: Path) -> None:
    # A path-filtered workflow reports no check at all on a PR that misses its paths,
    # so its gate could never be required. The jobs skip through an in-job diff instead.
    pull_request = _load(path)["on"]["pull_request"] or {}
    assert "paths" not in pull_request and "paths-ignore" not in pull_request


@pytest.mark.parametrize("path", REQUIRED_CHECK_WORKFLOWS + [PAUSE], ids=lambda p: p.name)
def test_changed_workflows_pin_every_action_by_commit_sha(path: Path) -> None:
    # Charter, Quality Gates: a workflow changed after the 2026-10-29 amendment pins every action.
    text = path.read_text(encoding="utf-8")
    for line in text.splitlines():
        stripped = line.strip().removeprefix("- ")
        if stripped.startswith("uses:") and not stripped.startswith("uses: ./"):
            assert PIN_LINE.search(stripped), line
            assert SHA_PIN.match(stripped.split()[1]), line


# --- ci.yml ----------------------------------------------------------------------------


def test_ci_reports_on_every_pull_request() -> None:
    types = _load(CI)["on"]["pull_request"]["types"]
    assert {"opened", "synchronize", "reopened", "labeled", "ready_for_review"} <= set(types)


def test_ci_concurrency_never_collapses_a_queue_run_into_main() -> None:
    group = _load(CI)["concurrency"]["group"]
    assert "'main'" not in group and "github.ref" in group


def test_ci_suite_runs_unconditionally_on_merge_group_and_is_pausable_on_prs() -> None:
    condition = " ".join(_load(CI)["jobs"]["suite"]["if"].split())
    assert condition.startswith("github.event_name != 'pull_request' || (")
    assert PAUSE_GUARD in condition
    assert "squad:passed" in condition and "squad:exhausted" in condition


def _resolve(env: dict[str, str], tmp_path: Path) -> dict[str, str]:
    base = {
        "EVENT": "",
        "PR_SHA": "",
        "PR_NUMBER": "",
        "PUSH_SHA": "",
        "MG_SHA": "",
        "MG_REF": "",
        "INPUT_SHA": "",
        "INPUT_PR": "",
    }
    return _run_step(_step(CI, "suite", "Resolve target"), {**base, **env}, tmp_path, tmp_path)


SHA_A = "a" * 40
SHA_B = "b" * 40


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
def test_ci_resolves_a_merge_group_to_its_sha_and_the_queued_pr(tmp_path: Path) -> None:
    out = _resolve(
        {
            "EVENT": "merge_group",
            "MG_SHA": SHA_A,
            "MG_REF": f"refs/heads/gh-readonly-queue/main/pr-178-{SHA_B}",
        },
        tmp_path,
    )
    assert out["__rc__"] == "0", out["__stderr__"]
    # A non-empty PR keeps ci-post.sh off main's baseline refresh (it treats pr="" as a main run).
    assert (out["sha"], out["pr"]) == (SHA_A, "178")


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
def test_ci_refuses_a_merge_group_ref_it_cannot_read(tmp_path: Path) -> None:
    out = _resolve({"EVENT": "merge_group", "MG_SHA": SHA_A, "MG_REF": "refs/heads/main"}, tmp_path)
    assert out["__rc__"] == "64"


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
@pytest.mark.parametrize(
    ("env", "expected"),
    [
        ({"EVENT": "pull_request", "PR_SHA": SHA_A, "PR_NUMBER": "12"}, (SHA_A, "12")),
        ({"EVENT": "push", "PUSH_SHA": SHA_B}, (SHA_B, "")),
        ({"EVENT": "workflow_dispatch", "INPUT_SHA": SHA_A, "INPUT_PR": "129"}, (SHA_A, "129")),
    ],
)
def test_ci_resolves_the_other_events_as_before(
    tmp_path: Path, env: dict[str, str], expected: tuple[str, str]
) -> None:
    out = _resolve(env, tmp_path)
    assert out["__rc__"] == "0", out["__stderr__"]
    assert (out["sha"], out["pr"]) == expected


def test_ci_fails_a_queue_run_whose_verdict_is_not_green() -> None:
    # ci-post.sh exits 0 for a red verdict (the verdict is a comment and a label), so the
    # job would pass the queue red. On merge_group the step fails unless the verdict is green.
    step = _step(CI, "suite", "Post the verdict (bin/ci-post.sh)")
    script = step["run"]
    assert 'if [ "$EVENT" = merge_group ]' in script
    assert r"^\[ci\] green @" in script
    assert step["env"]["EVENT"] == "${{ github.event_name }}"


def test_ci_scripts_read_event_values_from_env_not_interpolation() -> None:
    for name in ("Resolve target", "Post the verdict (bin/ci-post.sh)"):
        assert "${{" not in _step(CI, "suite", name)["run"], name


# --- acceptance and the harness gate ----------------------------------------------------


@pytest.mark.parametrize(
    ("path", "job"),
    [(ACCEPTANCE, "acceptance"), (HARNESS, "cross-repo-harness-tests")],
    ids=["acceptance", "harness"],
)
def test_single_job_gates_are_pausable_on_prs_only(path: Path, job: str) -> None:
    condition = " ".join(_load(path)["jobs"][job]["if"].split())
    assert condition == f"github.event_name != 'pull_request' || {PAUSE_GUARD}"


# --- in-job path detection (service.yml, docs-site.yml) ---------------------------------


def _git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=True
    ).stdout.strip()


def _two_commit_repo(tmp_path: Path, changed: str) -> tuple[Path, str, str]:
    """A clone whose origin has ``main`` at a base commit and a head that changes only ``changed``.

    ``changed`` of the form ``old>new`` renames the committed file ``old`` to ``new``.
    """
    clone, shas = _queue_repo(tmp_path, [changed])
    return clone, shas[0], shas[1]


def _detect_env(
    event: str, base: str, head: str, paused: str = "false", base_ref: str = "refs/heads/main"
) -> dict[str, str]:
    env = {
        "EVENT": event,
        "PR_BASE": "",
        "PR_HEAD": "",
        "MG_BASE": "",
        "MG_BASE_REF": "",
        "MG_HEAD": "",
        "PAUSED": paused,
    }
    if event == "pull_request":
        env.update(PR_BASE=base, PR_HEAD=head)
    elif event == "merge_group":
        env.update(MG_BASE=base, MG_HEAD=head, MG_BASE_REF=base_ref)
    return env


SERVICE_DETECT = (SERVICE, "detect", "Decide whether the change touches the Gradle build")
DOCS_DETECT = (DOCS, "build", "Decide whether the change touches the docs")


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
@pytest.mark.parametrize(
    ("detect", "changed", "expected"),
    [
        (SERVICE_DETECT, "service/command-cli/Main.java", "true"),
        (SERVICE_DETECT, "contracts/mission-write/openapi.yaml", "true"),
        (SERVICE_DETECT, "gradlew", "true"),
        (SERVICE_DETECT, "src/spec_kitty_events/producer_contract/rows.py", "true"),
        (SERVICE_DETECT, "docs/guides/contributing.md", "false"),
        (SERVICE_DETECT, "src/spec_kitty_events/status.py", "false"),
        (DOCS_DETECT, "docs/guides/contributing.md", "true"),
        (DOCS_DETECT, "site/package.json", "true"),
        (DOCS_DETECT, ".github/workflows/docs-site.yml", "true"),
        (DOCS_DETECT, "docs/changelog/unreleased/177-x.md", "false"),
        (DOCS_DETECT, "service/README.md", "false"),
        # A rename out of a watched path is still a change there (no rename detection).
        (SERVICE_DETECT, "service/old.java>elsewhere/old.txt", "true"),
        (DOCS_DETECT, "docs/old.md>elsewhere/old.txt", "true"),
    ],
)
@pytest.mark.parametrize("event", ["pull_request", "merge_group"])
def test_detect_runs_the_heavy_jobs_only_for_their_paths(
    tmp_path: Path, detect: tuple[Path, str, str], changed: str, expected: str, event: str
) -> None:
    clone, base, head = _two_commit_repo(tmp_path, changed)
    out = _run_step(_step(*detect), _detect_env(event, base, head), clone, tmp_path)
    assert out["__rc__"] == "0", out["__stderr__"]
    assert out["run"] == expected, out["__stderr__"]


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
@pytest.mark.parametrize("detect", [SERVICE_DETECT, DOCS_DETECT], ids=["service", "docs"])
@pytest.mark.parametrize("event", ["push", "schedule", "workflow_dispatch", "workflow_call"])
def test_detect_runs_everything_outside_pull_requests_and_queues(
    tmp_path: Path, detect: tuple[Path, str, str], event: str
) -> None:
    out = _run_step(_step(*detect), _detect_env(event, "", ""), tmp_path, tmp_path)
    assert (out["__rc__"], out["run"]) == ("0", "true"), out["__stderr__"]


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
@pytest.mark.parametrize("detect", [SERVICE_DETECT, DOCS_DETECT], ids=["service", "docs"])
def test_detect_runs_everything_when_it_cannot_fetch_the_shas(
    tmp_path: Path, detect: tuple[Path, str, str]
) -> None:
    clone, _, _ = _two_commit_repo(tmp_path, "README.md")
    out = _run_step(_step(*detect), _detect_env("merge_group", "c" * 40, "d" * 40), clone, tmp_path)
    assert (out["__rc__"], out["run"]) == ("0", "true"), out["__stderr__"]


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
@pytest.mark.parametrize("detect", [SERVICE_DETECT, DOCS_DETECT], ids=["service", "docs"])
def test_detect_skips_the_heavy_jobs_of_a_paused_pull_request(
    tmp_path: Path, detect: tuple[Path, str, str]
) -> None:
    clone, base, head = _two_commit_repo(tmp_path, "service/x.java")
    out = _run_step(
        _step(*detect), _detect_env("pull_request", base, head, paused="true"), clone, tmp_path
    )
    assert (out["__rc__"], out["run"]) == ("0", "false"), out["__stderr__"]


def test_service_detect_covers_every_push_path() -> None:
    # The push trigger keeps its path filter; the in-job diff must agree with it.
    paths = _load(SERVICE)["on"]["push"]["paths"]
    script = _step(*SERVICE_DETECT)["run"]
    pattern = re.search(r"PATTERN='([^']+)'", script)
    assert pattern, "detect must declare its PATTERN"
    regex = re.compile(pattern.group(1))
    for glob in paths:
        sample = glob.replace("/**", "/x/y.txt")
        assert regex.search(sample), glob


def test_service_jobs_skip_through_detect_and_the_gate_still_always_reports() -> None:
    jobs = _load(SERVICE)["jobs"]
    assert jobs["detect"]["outputs"]["run"] == "${{ steps.changes.outputs.run }}"
    for name in ("contract-bundle", "gradle", "native", "native-command-cli", "lock-interop"):
        assert "needs.detect.outputs.run == 'true'" in jobs[name]["if"], name
    assert jobs["service-gate"]["if"] == "always()"


def test_docs_build_steps_after_detect_skip_when_nothing_changed() -> None:
    steps = _load(DOCS)["jobs"]["build"]["steps"]
    names = [s.get("name") for s in steps]
    detect = names.index(DOCS_DETECT[2])
    for step in steps[detect + 1 :]:
        assert "steps.changes.outputs.run == 'true'" in step.get("if", ""), step.get("name")


def test_docs_never_uploads_or_deploys_from_a_queue_or_a_pr() -> None:
    workflow = _load(DOCS)
    upload = next(
        s
        for s in workflow["jobs"]["build"]["steps"]
        if s.get("name") == "Upload the Pages artifact"
    )
    assert "github.event_name == 'push' || github.event_name == 'workflow_dispatch'" in upload["if"]
    assert "github.ref == 'refs/heads/main'" in workflow["jobs"]["deploy"]["if"]


# --- pr:pause-ci (core #6961) ---------------------------------------------------------


def test_pause_workflow_reacts_only_to_its_label() -> None:
    workflow = _load(PAUSE)
    assert workflow["on"] == {"pull_request_target": {"types": ["labeled", "unlabeled"]}}
    assert workflow["permissions"] == {}
    for job in workflow["jobs"].values():
        assert "github.event.label.name == 'pr:pause-ci'" in job["if"]
        assert "github.repository == 'spec-kitty/spec-kitty-events'" in job["if"]


def test_pause_workflow_never_checks_out_or_runs_pr_code() -> None:
    # pull_request_target runs with a write token in the base repository's context.
    text = PAUSE.read_text(encoding="utf-8")
    workflow = _load(PAUSE)
    for job in workflow["jobs"].values():
        for step in job["steps"]:
            assert "uses" not in step, step
            assert "${{" not in step.get("run", ""), step.get("name")
    assert "checkout" not in text.lower().replace("never checks out", "")


def test_pause_fails_exactly_the_required_gates() -> None:
    script = _load(PAUSE)["jobs"]["pause"]["steps"][-1]["run"]
    gates = set(re.findall(r'"([^"]+)"', script.split("for gate in", 1)[1].split(";", 1)[0]))
    assert gates == REQUIRED_CHECKS


# --- pre-PR squad findings (M1-M3, m2, m3, m9) --------------------------------------------


def _all_steps(path: Path) -> list[tuple[str, dict[str, Any]]]:
    return [
        (job_id, step)
        for job_id, job in _load(path)["jobs"].items()
        for step in job.get("steps", [])
    ]


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
@pytest.mark.parametrize("path", REQUIRED_CHECK_WORKFLOWS + [PAUSE], ids=lambda p: p.name)
def test_every_run_block_is_valid_bash(path: Path) -> None:
    # Stands in for shellcheck/actionlint, which this environment does not have.
    for job_id, step in _all_steps(path):
        if "run" not in step:
            continue
        script = re.sub(r"\$\{\{.*?\}\}", "X", step["run"])
        result = subprocess.run(["bash", "-n"], input=script, capture_output=True, text=True)
        assert result.returncode == 0, f"{job_id}/{step.get('name')}: {result.stderr}"


@pytest.mark.parametrize("path", REQUIRED_CHECK_WORKFLOWS + [PAUSE], ids=lambda p: p.name)
def test_no_step_condition_reads_its_own_or_a_later_step(path: Path) -> None:
    for job_id, job in _load(path)["jobs"].items():
        pending = [s["id"] for s in job.get("steps", []) if "id" in s]
        for step in job.get("steps", []):
            condition = step.get("if", "")
            for step_id in pending:
                assert f"steps.{step_id}." not in condition, (job_id, step.get("name"))
            if "id" in step:
                pending.remove(step["id"])


def _verdict(tmp_path: Path, event: str, first_line: str | None, rc: int = 0) -> dict[str, str]:
    """Run ci.yml's verdict step with a stub bin/ci-post.sh that prints ``first_line``."""
    planning = tmp_path / "planning"
    (planning / "bin").mkdir(parents=True)
    stub = planning / "bin" / "ci-post.sh"
    echo = f"echo 'label skipped: PR head is now x'\necho '{first_line}'\n" if first_line else ""
    stub.write_text(f"#!/usr/bin/env bash\n{echo}exit {rc}\n", encoding="utf-8")
    stub.chmod(0o755)
    sha = "a" * 40
    run_dir = tmp_path / "ci-runs" / "spec-kitty-events" / sha
    run_dir.mkdir(parents=True)
    (run_dir / "exit").write_text("0\n", encoding="utf-8")
    env = {
        "EVENT": event,
        "SHA": sha,
        "PR": "178",
        "REPO": "spec-kitty-events",
        "SK_HOME": str(tmp_path),
    }
    step = _step(CI, "suite", "Post the verdict (bin/ci-post.sh)")
    return _run_step(step, env, planning, tmp_path)


GREEN = "[ci] green @" + "a" * 40 + " on vm"
RED = "[ci] red @" + "a" * 40 + " on vm"


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
@pytest.mark.parametrize(
    ("event", "first_line", "rc", "expected"),
    [
        ("merge_group", GREEN, 0, "0"),
        ("merge_group", "[ci] green @" + "a" * 40 + " (3 pre-existing on main) on vm", 0, "0"),
        ("merge_group", RED, 0, "1"),
        ("merge_group", None, 0, "1"),
        ("merge_group", GREEN, 78, "78"),
        ("pull_request", RED, 0, "0"),
        ("pull_request", None, 0, "0"),
        ("push", RED, 0, "0"),
        ("pull_request", GREEN, 1, "1"),
    ],
)
def test_the_verdict_step_fails_only_a_non_green_queue_run(
    tmp_path: Path, event: str, first_line: str | None, rc: int, expected: str
) -> None:
    out = _verdict(tmp_path, event, first_line, rc)
    assert out["__rc__"] == expected, out["__stderr__"]


def _queue_repo(tmp_path: Path, changes: list[str]) -> tuple[Path, list[str]]:
    """origin: main at a base commit, then one queue commit per change (a merge group)."""
    origin = tmp_path / "origin"
    origin.mkdir()
    _git(origin, "init", "-q", "-b", "main")
    _git(origin, "config", "user.email", "t@example.com")
    _git(origin, "config", "user.name", "t")
    for seeded in ("README.md", "service/old.java", "docs/old.md"):
        (origin / seeded).parent.mkdir(exist_ok=True)
        (origin / seeded).write_text("x\n", encoding="utf-8")
    _git(origin, "add", "-A")
    _git(origin, "commit", "-q", "-m", "base")
    _git(origin, "checkout", "-q", "-b", "gh-readonly-queue/main/pr-1")
    shas = [_git(origin, "rev-parse", "HEAD")]
    for changed in changes:
        if ">" in changed:
            old, new = changed.split(">")
            (origin / new).parent.mkdir(parents=True, exist_ok=True)
            _git(origin, "mv", old, new)
            _git(origin, "commit", "-q", "-m", changed)
            shas.append(_git(origin, "rev-parse", "HEAD"))
            continue
        target = origin / changed
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("changed\n", encoding="utf-8")
        _git(origin, "add", "-A")
        _git(origin, "commit", "-q", "-m", changed)
        shas.append(_git(origin, "rev-parse", "HEAD"))
    _git(origin, "config", "uploadpack.allowAnySHA1InWant", "true")
    clone = tmp_path / "clone"
    _git(tmp_path, "clone", "-q", "--depth", "1", "--branch", "main", origin.as_uri(), str(clone))
    return clone, shas


@pytest.mark.skipif(not HAS_BASH, reason="bash not available")
@pytest.mark.parametrize(
    ("detect", "first"), [(SERVICE_DETECT, "service/x.java"), (DOCS_DETECT, "docs/x.md")]
)
def test_a_queue_entry_builds_what_an_earlier_pr_in_its_group_changed(
    tmp_path: Path, detect: tuple[Path, str, str], first: str
) -> None:
    # Entry 2's base_sha may be entry 1's head; the diff is taken against main (base_ref).
    clone, shas = _queue_repo(tmp_path, [first, "tests/only.py"])
    out = _run_step(_step(*detect), _detect_env("merge_group", shas[1], shas[2]), clone, tmp_path)
    assert (out["__rc__"], out["run"]) == ("0", "true"), out["__stderr__"]


def test_docs_detect_covers_every_push_path() -> None:
    paths = [p for p in _load(DOCS)["on"]["push"]["paths"] if not p.startswith("!")]
    pattern = re.search(r"PATTERN='([^']+)'", _step(*DOCS_DETECT)["run"])
    assert pattern
    regex = re.compile(pattern.group(1))
    for glob in paths:
        assert regex.search(glob.replace("/**", "/x/y.txt")), glob
    assert not regex.search("scripts/unrelated.py")


def test_the_docs_gate_has_an_unambiguous_check_name() -> None:
    # `build` also names jobs in the tag-only release workflows.
    assert _load(DOCS)["jobs"]["build"]["name"] == "docs-build"


# --- resume (M3): un-pausing re-runs every gate, not only cancelled runs -----------------

GATE_WORKFLOWS = [
    "ci.yml",
    "acceptance.yml",
    "cross-repo-harness-tests.yml",
    "service.yml",
    "docs-site.yml",
]


def _fake_gh(tmp_path: Path, runs: dict[str, list[dict[str, Any]]], label_at: str) -> Path:
    """A ``gh`` stub: answers the issue-events and run-list queries, logs every rerun."""
    import json

    bindir = tmp_path / "bin"
    bindir.mkdir()
    data = tmp_path / "runs.json"
    data.write_text(json.dumps(runs), encoding="utf-8")
    log = tmp_path / "gh.log"
    stub = bindir / "gh"
    stub.write_text(
        "#!/usr/bin/env python3\n"
        "import json, sys\n"
        "args = sys.argv[1:]\n"
        f"log = open({str(log)!r}, 'a')\n"
        "if args[:2] == ['run', 'rerun']:\n"
        "    log.write('rerun ' + args[2] + '\\n'); sys.exit(0)\n"
        "if args[0] == 'api' and '/events' in args[1]:\n"
        f"    print({label_at!r}); sys.exit(0)\n"
        "if args[:2] == ['run', 'list']:\n"
        "    wf = args[args.index('--workflow') + 1]\n"
        f"    print(json.dumps(json.load(open({str(data)!r})).get(wf, []))); sys.exit(0)\n"
        "sys.exit(2)\n",
        encoding="utf-8",
    )
    stub.chmod(0o755)
    return bindir


@pytest.mark.skipif(not HAS_BASH or shutil.which("jq") is None, reason="bash or jq missing")
def test_resume_reruns_each_gate_workflows_latest_run_from_before_the_pause(
    tmp_path: Path,
) -> None:
    runs = {
        "ci.yml": [
            {"databaseId": 1, "createdAt": "2026-11-01T09:00:00Z"},
            {"databaseId": 2, "createdAt": "2026-11-01T09:30:00Z"},
            {"databaseId": 3, "createdAt": "2026-11-01T10:00:05Z"},
        ],
        "acceptance.yml": [{"databaseId": 10, "createdAt": "2026-11-01T09:00:00Z"}],
        "service.yml": [{"databaseId": 20, "createdAt": "2026-11-01T10:05:00Z"}],
    }
    bindir = _fake_gh(tmp_path, runs, "2026-11-01T10:00:00Z")
    step = _load(PAUSE)["jobs"]["resume"]["steps"][-1]
    env = {
        "PATH": f"{bindir}:{os.environ['PATH']}",
        "REPO": "spec-kitty/spec-kitty-events",
        "HEAD_SHA": "a" * 40,
        "PR_NUMBER": "178",
    }
    out = _run_step(step, env, tmp_path, tmp_path)
    assert out["__rc__"] == "0", out["__stderr__"]
    assert (tmp_path / "gh.log").read_text(encoding="utf-8").split() == [
        "rerun",
        "2",
        "rerun",
        "10",
    ]
    # service.yml has no run from before the pause; the step says so instead of guessing.
    assert "service.yml" in out["__stderr__"]


def test_resume_covers_exactly_the_gate_workflows() -> None:
    script = _load(PAUSE)["jobs"]["resume"]["steps"][-1]["run"]
    listed = re.search(r"for wf in ([^;]+);", script)
    assert listed and listed.group(1).split() == GATE_WORKFLOWS
