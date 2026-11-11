"""Release-protection audit: settings drift and release provenance, offline."""

from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "release"))
import release_protection_audit as audit  # noqa: E402

LIVE = ROOT / "tests" / "fixtures" / "release_protection" / "live"
EXPECTED_PATH = ROOT / "scripts" / "release" / "release-protection-expected.json"
REPO = "spec-kitty/spec-kitty-events"
PAYLOAD = b"payload"
PAYLOAD_SHA = hashlib.sha256(PAYLOAD).hexdigest()


def load(name: str) -> Any:
    return json.loads((LIVE / name).read_text(encoding="utf-8"))


def raw_inputs() -> dict[str, Any]:
    return {
        "environments": load("environments.json"),
        "service_release": load("env_service_release.json"),
        "branch_policies": load("deployment_branch_policies.json"),
        "rulesets": [load("rs_main.json"), load("rs_tags.json")],
    }


def normalised(raw: dict[str, Any] | None = None) -> dict[str, Any]:
    raw = raw or raw_inputs()
    return audit.normalise_settings(
        REPO,
        raw["environments"],
        raw["service_release"],
        raw["branch_policies"],
        raw["rulesets"],
    )


@pytest.fixture
def expected() -> dict[str, Any]:
    data: dict[str, Any] = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))
    return data


@pytest.fixture
def releases() -> list[dict[str, Any]]:
    data: list[dict[str, Any]] = load("releases.json")
    return data


class FakeVerifier:
    def __init__(self, bad: set[str] | None = None) -> None:
        self.calls: list[tuple[str, str]] = []
        self.bad = bad or set()

    def __call__(self, tag: str, asset: dict[str, Any]) -> audit.AssetVerdict:
        self.calls.append((tag, asset["name"]))
        if asset["name"] in self.bad:
            return audit.AssetVerdict(False, "attestation verification failed: nope")
        return audit.AssetVerdict(True, "")


def service_release(
    tag: str, author: str = audit.BOT, assets: list[str] | None = None, prerelease: bool = False
) -> dict[str, Any]:
    names = ["tool-linux", "tool-linux.sha256", "SHA256SUMS"] if assets is None else assets
    return {
        "tag_name": tag,
        "prerelease": prerelease,
        "draft": False,
        "author": {"login": author},
        "assets": [
            {"name": n, "size": 1, "digest": None, "uploader": {"login": audit.BOT}} for n in names
        ],
    }


# ---------------------------------------------------------------- 1. snapshot


def test_snapshot_matches_committed_expected_file(expected: dict[str, Any]) -> None:
    assert normalised() == expected
    assert audit.diff_settings(expected, normalised()) == []
    # Positive reading of the committed values (the fixture is a real capture).
    main_rules = {r["type"]: r for r in expected["rulesets"]["main"]["rules"]}
    assert main_rules["pull_request"]["parameters"]["required_approving_review_count"] == 0
    checks = main_rules["required_status_checks"]["parameters"]["required_status_checks"]
    assert sorted(c["context"] for c in checks) == [
        "acceptance",
        "cross-repo-harness-tests",
        "docs-build",
        "service-gate",
    ]
    env = expected["service_release"]
    assert env["can_admins_bypass"] is False
    assert env["reviewers"] == ["Team:admins", "User:stijn-dejongh"]
    assert env["prevent_self_review"] is True
    assert env["deployment_policies"] == [{"name": "service-v*", "type": "tag"}]
    assert "bypass_actors" not in json.dumps(expected)


# ------------------------------------------------------------ 2. AC 2 leaves


Path_ = tuple[Any, ...]


def leaf_paths(tree: Any, prefix: Path_ = ()) -> list[Path_]:
    """Dict leaves; a list is one leaf, plus each scalar element of a scalar list."""
    if isinstance(tree, dict):
        out: list[Path_] = []
        for key in sorted(tree):
            out.extend(leaf_paths(tree[key], (*prefix, key)))
        return out
    leaves: list[Path_] = [prefix]
    if isinstance(tree, list) and all(isinstance(v, str) for v in tree):
        leaves.extend((*prefix, i) for i in range(len(tree)))
    return leaves


def mutate(tree: dict[str, Any], path: Path_) -> None:
    node: Any = tree
    for key in path[:-1]:
        node = node[key]
    value = node[path[-1]]
    if isinstance(value, bool):
        node[path[-1]] = not value
    elif isinstance(value, (int, float)):
        node[path[-1]] = value + 1
    elif isinstance(value, str):
        node[path[-1]] = value + "-changed"
    elif isinstance(value, list):
        node[path[-1]] = value[:-1] if value else ["x"]
    else:
        node[path[-1]] = "x"


def _leaves() -> list[Path_]:
    return leaf_paths(json.loads(EXPECTED_PATH.read_text(encoding="utf-8")))


@pytest.mark.parametrize("path", _leaves(), ids=lambda p: ".".join(map(str, p)))
def test_every_expected_leaf_drifting_gives_exactly_one_failure(path: Path_) -> None:
    live = normalised()
    # positive control: untouched live matches the committed file
    assert audit.diff_settings(json.loads(EXPECTED_PATH.read_text()), live) == []
    changed = json.loads(EXPECTED_PATH.read_text())
    mutate(changed, path)
    findings = audit.diff_settings(changed, live)
    dotted = ".".join(str(k) for k in path if not isinstance(k, int))
    assert len(findings) == 1, f"{path}: {findings}"
    assert findings[0].severity == "failure"
    assert findings[0].subject.startswith(dotted)


def test_leaf_enumeration_is_not_vacuous() -> None:
    assert len(_leaves()) >= 30


# -------------------------------------------------------- 3. live drift cases


def drift_cases() -> list[tuple[str, Any, str]]:
    def reviewer_removed(raw: dict[str, Any]) -> None:
        rule = raw["service_release"]["protection_rules"][0]
        rule["reviewers"] = rule["reviewers"][:1]

    def self_review(raw: dict[str, Any]) -> None:
        raw["service_release"]["protection_rules"][0]["prevent_self_review"] = False

    def admins(raw: dict[str, Any]) -> None:
        raw["service_release"]["can_admins_bypass"] = True

    def tag_policy(raw: dict[str, Any]) -> None:
        raw["branch_policies"]["branch_policies"][0]["name"] = "*"

    def evaluate(raw: dict[str, Any]) -> None:
        raw["rulesets"][0]["enforcement"] = "evaluate"

    def third(raw: dict[str, Any]) -> None:
        extra = copy.deepcopy(raw["rulesets"][1])
        extra["name"] = "extra"
        raw["rulesets"].append(extra)

    def removed(raw: dict[str, Any]) -> None:
        raw["rulesets"].pop()

    def env(raw: dict[str, Any]) -> None:
        raw["environments"]["environments"].append({"id": 9, "name": "new-env"})

    def review_count(raw: dict[str, Any]) -> None:
        rules = raw["rulesets"][0]["rules"]
        for r in rules:
            if r["type"] == "pull_request":
                r["parameters"]["required_approving_review_count"] = 2

    def unknown_param(raw: dict[str, Any]) -> None:
        for r in raw["rulesets"][0]["rules"]:
            if r["type"] == "pull_request":
                r["parameters"]["brand_new_flag"] = True

    return [
        ("reviewer removed", reviewer_removed, "service_release.reviewers"),
        ("self review allowed", self_review, "service_release.prevent_self_review"),
        ("admins bypass", admins, "service_release.can_admins_bypass"),
        ("tag policy widened", tag_policy, "service_release.deployment_policies"),
        ("ruleset evaluate", evaluate, "rulesets.main.enforcement"),
        ("third ruleset", third, "rulesets.extra"),
        ("ruleset removed", removed, "rulesets.service-release-tags"),
        ("new environment", env, "environments"),
        ("review count", review_count, "rulesets.main.rules"),
        ("unknown parameter kept", unknown_param, "rulesets.main.rules"),
    ]


@pytest.mark.parametrize(("label", "change", "path"), drift_cases(), ids=lambda x: str(x)[:30])
def test_live_drift_is_reported_at_the_right_path(
    expected: dict[str, Any], label: str, change: Any, path: str
) -> None:
    assert audit.diff_settings(expected, normalised()) == []  # positive control
    raw = raw_inputs()
    change(raw)
    findings = audit.diff_settings(expected, normalised(raw))
    assert findings, label
    assert all(f.severity == "failure" and f.kind == "settings" for f in findings)
    assert any(f.subject.startswith(path) for f in findings), (label, findings)


def test_missing_and_unexpected_messages(expected: dict[str, Any]) -> None:
    raw = raw_inputs()
    raw["rulesets"].pop()
    missing = audit.diff_settings(expected, normalised(raw))
    assert [f.message for f in missing] == ["missing live"]
    del expected["rulesets"]["service-release-tags"]
    unexpected = audit.diff_settings(expected, normalised())
    assert unexpected[0].message.startswith("unexpected live value ")
    changed = audit.diff_settings({"a": [1]}, {"a": [2]})
    assert changed[0].message == "expected [1], live [2]"


# ------------------------------------------------------ 4. volatile fields


def test_volatile_fields_and_bypass_lists_are_ignored(expected: dict[str, Any]) -> None:
    assert audit.diff_settings(expected, normalised()) == []  # positive control
    raw = raw_inputs()
    for rule in raw["service_release"]["protection_rules"]:
        rule["id"] += 1000
        rule["node_id"] = "other"
        for entry in rule.get("reviewers", []):
            entry["reviewer"]["avatar_url"] = "https://example.invalid/a.png"
    for rs in raw["rulesets"]:
        rs["id"] += 1
        rs["node_id"] = "other"
        rs["updated_at"] = "2030-01-01T00:00:00Z"
        rs["_links"] = {"self": {"href": "x"}}
        rs["current_user_can_bypass"] = "always"
        rs["bypass_actors"] = [
            {"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}
        ]
    assert audit.diff_settings(expected, normalised(raw)) == []
    assert "bypass_actors" not in json.dumps(normalised(raw))


# --------------------------------------------------------- 5. AC 3 releases


def test_published_release_candidates_pass_and_only_assets_are_verified(
    releases: list[dict[str, Any]],
) -> None:
    verifier = FakeVerifier()
    assert audit.audit_releases(releases, verifier) == []
    service = [r for r in releases if r["tag_name"].startswith("service-v")]
    wanted = sorted(
        (r["tag_name"], a["name"])
        for r in service
        for a in r["assets"]
        if a["name"] != "SHA256SUMS" and not a["name"].endswith(".sha256")
    )
    assert wanted and sorted(verifier.calls) == wanted
    assert not any(t.startswith("v") for t, _ in verifier.calls)
    assert not any(n == "SHA256SUMS" or n.endswith(".sha256") for _, n in verifier.calls)
    # the checksum assets exist in the fixture, so the exclusion is real
    assert any(a["name"].endswith(".sha256") for r in service for a in r["assets"])


@pytest.mark.parametrize(
    ("tag", "severity"), [("service-v1.0.0", "failure"), ("service-v1.0.0-rc.1", "warning")]
)
def test_release_faults_have_the_severity_of_the_release_kind(tag: str, severity: str) -> None:
    clean = audit.audit_releases([service_release(tag)], FakeVerifier())
    assert clean == []  # positive control

    foreign = audit.audit_releases([service_release(tag, author="someone")], FakeVerifier())
    assert [(f.severity, f.subject) for f in foreign] == [(severity, tag)]
    assert "someone" in foreign[0].message and audit.BOT in foreign[0].message

    bad = audit.audit_releases([service_release(tag)], FakeVerifier(bad={"tool-linux"}))
    assert [(f.severity, f.subject) for f in bad] == [(severity, f"{tag}/tool-linux")]

    empty = audit.audit_releases([service_release(tag, assets=[])], FakeVerifier())
    assert [(f.severity, f.subject, f.message) for f in empty] == [(severity, tag, "no assets")]


def test_tag_outside_the_release_line_fails_and_other_tags_are_ignored() -> None:
    verifier = FakeVerifier()
    found = audit.audit_releases(
        [service_release("service-vfoo"), service_release("v1.2.3", author="someone")], verifier
    )
    assert [(f.severity, f.subject) for f in found] == [("failure", "service-vfoo")]
    assert verifier.calls == []


def test_prerelease_flag_does_not_decide_the_classification() -> None:
    final = service_release("service-v1.0.0", author="someone", prerelease=True)
    found = audit.audit_releases([final], FakeVerifier())
    assert [f.severity for f in found] == ["failure"]
    rc = service_release("service-v1.0.0-rc.1", author="someone", prerelease=False)
    assert [f.severity for f in audit.audit_releases([rc], FakeVerifier())] == ["warning"]


# ------------------------------------------------------------- fake gh runner


class FakeGh:
    """Serves fixtures by path; records every argv; never spawns a process."""

    def __init__(
        self,
        raw: dict[str, Any] | None = None,
        releases: list[dict[str, Any]] | None = None,
        fail: str | None = None,
        attest_rc: int = 0,
    ) -> None:
        self.raw = raw or raw_inputs()
        self.releases = releases if releases is not None else self.default_releases()
        self.fail = fail
        self.attest_rc = attest_rc
        self.calls: list[list[str]] = []

    @staticmethod
    def default_releases() -> list[dict[str, Any]]:
        data: list[dict[str, Any]] = load("releases.json")
        for r in data:
            for a in r["assets"]:
                a["digest"] = f"sha256:{PAYLOAD_SHA}"
        return data

    def __call__(self, args: list[str]) -> subprocess.CompletedProcess[str]:
        self.calls.append(args)
        assert args[0] == "gh"
        if args[1] == "api":
            return self.api(args)
        if args[1:3] == ["release", "download"]:
            if self.fail == "download":
                return subprocess.CompletedProcess(args, 1, "", "boom")
            (Path(args[args.index("--dir") + 1]) / args[args.index("--pattern") + 1]).write_bytes(
                PAYLOAD
            )
            return subprocess.CompletedProcess(args, 0, "", "")
        if args[1:3] == ["attestation", "verify"]:
            err = "line one\nno matching attestation" if self.attest_rc else ""
            return subprocess.CompletedProcess(args, self.attest_rc, "", err)
        raise AssertionError(f"unexpected gh call {args}")

    def api(self, args: list[str]) -> subprocess.CompletedProcess[str]:
        if self.fail == "api":
            return subprocess.CompletedProcess(args, 1, "", "HTTP 500")
        paginate = "--paginate" in args and "--slurp" in args
        path = args[-1]
        base = f"repos/{REPO}"
        env = f"{base}/environments/service-release"
        table: dict[str, tuple[Any, bool]] = {
            f"{base}/rulesets": (
                [{"id": rs["id"], "name": rs["name"]} for rs in self.raw["rulesets"]],
                True,
            ),
            f"{base}/environments": (self.raw["environments"], True),
            env: (self.raw["service_release"], False),
            f"{env}/deployment-branch-policies": (self.raw["branch_policies"], True),
            f"{base}/releases?per_page=100": (self.releases, True),
        }
        for rs in self.raw["rulesets"]:
            table[f"{base}/rulesets/{rs['id']}"] = (rs, False)
        data, is_list = table[path]
        assert paginate == is_list, path
        body = [data] if paginate else data
        return subprocess.CompletedProcess(args, 0, json.dumps(body), "")


# ----------------------------------------------------------------- 6. errors


def test_api_failure_exits_2_with_error_line(
    capsys: pytest.CaptureFixture[str],
) -> None:
    ok = audit.main(["--repo", REPO, "--expected", str(EXPECTED_PATH)], runner=FakeGh())
    assert ok == 0  # positive control
    capsys.readouterr()
    code = audit.main(["--repo", REPO, "--expected", str(EXPECTED_PATH)], runner=FakeGh(fail="api"))
    out = capsys.readouterr().out
    assert code == 2 and out.startswith("ERROR ") and "HTTP 500" in out


def test_invalid_json_is_an_error() -> None:
    def runner(args: list[str]) -> subprocess.CompletedProcess[str]:
        return subprocess.CompletedProcess(args, 0, "not json", "")

    with pytest.raises(audit.AuditError):
        audit.gh_json(runner, "repos/x")


def test_download_failure_on_a_prerelease_is_an_error_not_a_warning(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    only_rc = [r for r in FakeGh.default_releases() if r["tag_name"] == "service-v0.1.0-rc.2"]
    good = audit.main(
        ["--repo", REPO, "--expected", str(EXPECTED_PATH)], runner=FakeGh(releases=only_rc)
    )
    assert good == 0  # positive control
    capsys.readouterr()
    code = audit.main(
        ["--repo", REPO, "--expected", str(EXPECTED_PATH)],
        runner=FakeGh(releases=only_rc, fail="download"),
    )
    out = capsys.readouterr().out
    assert code == 2 and "ERROR" in out and "WARN" not in out


# ------------------------------------------------- 7. production verifier


ASSET = {"name": "tool-linux", "digest": f"sha256:{PAYLOAD_SHA}"}


def test_verifier_success_builds_the_exact_attestation_argv(tmp_path: Path) -> None:
    gh = FakeGh()
    verdict = audit.gh_asset_verifier(gh, REPO, tmp_path)("service-v0.1.0-rc.2", ASSET)
    assert verdict == audit.AssetVerdict(True, "")
    assert not (tmp_path / "tool-linux").exists()
    attest = next(c for c in gh.calls if c[1:3] == ["attestation", "verify"])
    assert attest == [
        "gh",
        "attestation",
        "verify",
        str(tmp_path / "tool-linux"),
        "--repo",
        REPO,
        "--signer-workflow",
        "spec-kitty/spec-kitty-events/.github/workflows/service-release.yml",
        "--source-ref",
        "refs/tags/service-v0.1.0-rc.2",
        "--deny-self-hosted-runners",
    ]
    download = next(c for c in gh.calls if c[1:3] == ["release", "download"])
    assert download[3:5] == ["service-v0.1.0-rc.2", "--repo"] and "--clobber" in download


def test_verifier_digest_mismatch_is_not_ok_and_cleans_up(tmp_path: Path) -> None:
    gh = FakeGh()
    asset = {"name": "tool-linux", "digest": "sha256:" + "0" * 64}
    verdict = audit.gh_asset_verifier(gh, REPO, tmp_path)("service-v1.0.0", asset)
    assert not verdict.ok and verdict.reason.startswith("digest mismatch: api ")
    assert PAYLOAD_SHA in verdict.reason
    assert not (tmp_path / "tool-linux").exists()
    assert not any(c[1:3] == ["attestation", "verify"] for c in gh.calls)


def test_verifier_attestation_failure_is_not_ok_and_cleans_up(tmp_path: Path) -> None:
    gh = FakeGh(attest_rc=1)
    verdict = audit.gh_asset_verifier(gh, REPO, tmp_path)("service-v1.0.0", ASSET)
    assert verdict == audit.AssetVerdict(
        False, "attestation verification failed: no matching attestation"
    )
    assert not (tmp_path / "tool-linux").exists()


def test_verifier_without_api_digest_skips_the_digest_check(tmp_path: Path) -> None:
    verdict = audit.gh_asset_verifier(FakeGh(), REPO, tmp_path)(
        "service-v1.0.0", {"name": "tool-linux", "digest": None}
    )
    assert verdict.ok


# ---------------------------------------------------------- 8. CLI end to end


def run_cli(
    tmp_path: Path, gh: FakeGh, capsys: pytest.CaptureFixture[str], *extra: str
) -> tuple[int, str, str]:
    summary = tmp_path / "summary.md"
    code = audit.main(
        ["--repo", REPO, "--expected", str(EXPECTED_PATH), "--summary", str(summary), *extra],
        runner=gh,
    )
    return code, capsys.readouterr().out, summary.read_text(encoding="utf-8")


def test_cli_clean_run_exits_0_and_writes_summary(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    gh = FakeGh()
    code, out, summary = run_cli(tmp_path, gh, capsys)
    assert code == 0 and "FAIL" not in out
    assert summary.startswith("## Release-protection audit") and "No findings." in summary
    # the whole path ran: attestation was checked for the non-checksum assets
    assert any(c[1:3] == ["attestation", "verify"] for c in gh.calls)


def test_cli_drift_exits_1_and_reports_it(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    raw = raw_inputs()
    raw["service_release"]["can_admins_bypass"] = True
    code, out, summary = run_cli(tmp_path, FakeGh(raw=raw), capsys)
    assert code == 1
    assert "FAIL settings service_release.can_admins_bypass: expected false, live true" in out
    assert "service_release.can_admins_bypass" in summary and "No findings." not in summary


def test_cli_prerelease_fault_warns_and_exits_0(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    code, out, _ = run_cli(tmp_path, FakeGh(attest_rc=1), capsys, "--skip-settings")
    assert code == 0 and "WARN release service-v0.1.0-rc" in out and "FAIL" not in out


def test_cli_skip_flags(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    gh = FakeGh()
    code, _, _ = run_cli(tmp_path, gh, capsys, "--skip-releases")
    assert code == 0
    assert not any(c[1:3] == ["attestation", "verify"] for c in gh.calls)
    gh = FakeGh()
    run_cli(tmp_path, gh, capsys, "--skip-settings")
    assert not any("rulesets" in c[-1] for c in gh.calls)


def test_missing_service_release_environment_is_drift_not_an_error() -> None:
    raw = raw_inputs()
    raw["environments"]["environments"] = [
        e for e in raw["environments"]["environments"] if e["name"] != "service-release"
    ]
    live = audit.read_live_settings(FakeGh(raw=raw), REPO)
    findings = audit.diff_settings(json.loads(EXPECTED_PATH.read_text()), live)
    assert any(f.subject == "environments" for f in findings)
    assert any(f.subject.startswith("service_release.") for f in findings)


# -------------------------------------------------------------- 9. determinism


def test_diff_and_release_audit_are_deterministic(
    expected: dict[str, Any], releases: list[dict[str, Any]]
) -> None:
    raw = raw_inputs()
    raw["service_release"]["can_admins_bypass"] = True
    raw["rulesets"][0]["enforcement"] = "evaluate"
    live = normalised(raw)
    first = audit.diff_settings(expected, live)
    assert len(first) >= 2 and first == audit.diff_settings(expected, live)
    assert [f.subject for f in first] == sorted(f.subject for f in first)

    mixed = copy.deepcopy(releases) + [service_release("service-v2.0.0", author="x")]
    one = audit.audit_releases(mixed, FakeVerifier(bad={"tool-linux"}))
    two = audit.audit_releases(list(reversed(mixed)), FakeVerifier(bad={"tool-linux"}))
    assert one and one == two
