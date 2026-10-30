"""Static checks of the service CI and the service-v* release line (ADR 0003, mission WP04).

The asset contract is the table in ADR 0003 (its durable home); the assets script must produce
exactly that set, deterministically.
"""

from __future__ import annotations

import fnmatch
import hashlib
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
SERVICE = WORKFLOWS / "service.yml"
RELEASE = WORKFLOWS / "service-release.yml"
PYPI = WORKFLOWS / "publish-pypi.yml"
SCRIPT = ROOT / "scripts" / "release" / "service-release-assets.sh"
ADR = ROOT / "docs" / "adr" / "0003-gradle-build-and-service-release-line.md"

SHA_PIN = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
TARGETS = ["linux-x64", "linux-arm64", "macos-arm64", "windows-x64"]
SERVICE_JOBS = ["contract-bundle", "native", "lock-interop", "nightly-corpus"]


def _has_gnu_tar() -> bool:
    if shutil.which("tar") is None:
        return False
    return "GNU" in subprocess.run(["tar", "--version"], capture_output=True, text=True).stdout


needs_tools = pytest.mark.skipif(
    shutil.which("bash") is None or shutil.which("sha256sum") is None or not _has_gnu_tar(),
    reason="the assets script needs bash, GNU tar and sha256sum",
)


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


@pytest.mark.parametrize("path", [SERVICE, RELEASE], ids=lambda p: p.name)
def test_every_action_is_pinned_to_a_full_commit_sha(path: Path) -> None:
    uses = _uses(_load(path)["jobs"])
    assert uses
    for ref in uses:
        if ref.startswith("./"):
            continue  # a local reusable workflow
        assert SHA_PIN.match(ref), f"{path.name}: {ref} is not pinned to a 40-hex commit SHA"


@pytest.mark.parametrize("path", [SERVICE, RELEASE], ids=lambda p: p.name)
def test_every_pin_carries_a_version_comment(path: Path) -> None:
    for line in path.read_text(encoding="utf-8").splitlines():
        if re.search(r"uses: [^.\s][^@\s]*@", line):
            assert re.search(r"@[0-9a-f]{40} # v\d+\.\d+\.\d+$", line), line


@pytest.mark.parametrize("path", [SERVICE, RELEASE], ids=lambda p: p.name)
def test_workflow_token_is_read_only_by_default(path: Path) -> None:
    assert _load(path)["permissions"] == {"contents": "read"}


def test_only_the_publish_job_can_write() -> None:
    jobs = _load(RELEASE)["jobs"]
    writers = {name for name, job in jobs.items() if "permissions" in job}
    assert writers == {"publish"}
    assert jobs["publish"]["permissions"] == {
        "contents": "write",
        "id-token": "write",
        "attestations": "write",
    }
    assert jobs["publish"]["if"] == "github.event_name == 'push'"


def test_release_triggers_only_on_service_tags() -> None:
    on = _on(_load(RELEASE))
    assert set(on) == {"push", "workflow_dispatch"}
    assert on["push"] == {"tags": ["service-v*"]}


def test_package_release_tags_do_not_match_service_tags() -> None:
    # GitHub's tag filter is a glob anchored at the start of the ref name, so publish-pypi's `v*`
    # does not match service-v1.0.0; fnmatch has the same anchoring for these patterns.
    tags = _on(_load(PYPI))["push"]["tags"]
    assert tags
    for pattern in tags:
        assert not fnmatch.fnmatchcase("service-v1.0.0", pattern), pattern
    assert fnmatch.fnmatchcase("service-v1.0.0", "service-v*")
    assert not fnmatch.fnmatchcase("v11.3.0", "service-v*")


def test_service_jobs_are_guarded_by_detect() -> None:
    jobs = _load(SERVICE)["jobs"]
    for name in SERVICE_JOBS:
        needs = jobs[name]["needs"]
        assert "detect" in ([needs] if isinstance(needs, str) else needs), name
        assert "needs.detect.outputs.service == 'true'" in jobs[name]["if"], name
    # The binding's build runs today, without the guard.
    assert "needs.detect.outputs.service == 'true'" not in jobs["gradle"]["if"]


def test_gradle_job_runs_the_aggregate_verify_on_jdk_25() -> None:
    steps = _load(SERVICE)["jobs"]["gradle"]["steps"]
    java = next(s for s in steps if s.get("uses", "").startswith("actions/setup-java@"))
    assert java["with"] == {"distribution": "temurin", "java-version": "25"}
    assert any(s.get("run") == "./gradlew --no-daemon verifyAll" for s in steps)


def test_gradle_job_survives_the_skipped_contract_bundle() -> None:
    # Before cutover contract-bundle is skipped; without a status function GitHub ANDs an implicit
    # success() into `if:` and would skip the one job that runs today.
    condition = _load(SERVICE)["jobs"]["gradle"]["if"]
    assert "!cancelled()" in condition
    assert "needs.contract-bundle.result != 'failure'" in condition


def test_release_runs_execute_no_core_code() -> None:
    # lock-interop runs core's Python at the pinned SHA; a release run must not, so a compromised
    # peer cannot touch the artifacts the release attests.
    condition = _load(SERVICE)["jobs"]["lock-interop"]["if"]
    assert "inputs.release-version == ''" in condition


def test_release_runs_restore_no_gradle_cache() -> None:
    jobs = _load(SERVICE)["jobs"]
    gradle_steps = [
        step
        for job in jobs.values()
        for step in job.get("steps", [])
        if step.get("uses", "").startswith("gradle/actions/setup-gradle@")
    ]
    assert gradle_steps
    for step in gradle_steps:
        assert step["with"]["cache-disabled"] == "${{ inputs.release-version != '' }}"


def test_publish_uploads_only_the_listed_assets() -> None:
    publish = _load(RELEASE)["jobs"]["publish"]
    script = "\n".join(step.get("run", "") for step in publish["steps"])
    assert "dist/*" not in script
    assert "awk '{print $2}' dist/SHA256SUMS" in script
    assert "--signer-workflow" in script


def test_gate_needs_every_job_and_tolerates_skips() -> None:
    jobs = _load(SERVICE)["jobs"]
    gate = jobs["service-gate"]
    assert gate["if"] == "always()"
    assert set(gate["needs"]) == set(jobs) - {"service-gate"}
    script = gate["steps"][0]["run"]
    assert "success|skipped" in script


def test_native_matrix_covers_the_four_targets() -> None:
    include = _load(SERVICE)["jobs"]["native"]["strategy"]["matrix"]["include"]
    assert {(i["target"], i["runner"]) for i in include} == {
        ("linux-x64", "ubuntu-24.04"),
        ("linux-arm64", "ubuntu-24.04-arm"),
        ("macos-arm64", "macos-15"),
        ("windows-x64", "windows-2025"),
    }


def test_release_calls_the_service_build_with_the_version() -> None:
    jobs = _load(RELEASE)["jobs"]
    assert jobs["build"]["uses"] == "./.github/workflows/service.yml"
    assert jobs["build"]["with"] == {"release-version": "${{ needs.gate.outputs.ver }}"}
    assert "workflow_call" in _on(_load(SERVICE))
    gate = "\n".join(s.get("run", "") for s in jobs["gate"]["steps"])
    assert "service-v* releases need the cutover import" in gate
    assert 'git merge-base --is-ancestor "$GITHUB_SHA" origin/main' in gate


# --- the asset contract ---------------------------------------------------------------------------


def _adr_assets(ver: str) -> list[str]:
    """The backticked asset names of the ADR 0003 'Release assets' table, for <ver>."""
    section = ADR.read_text(encoding="utf-8").split("### Release assets", 1)[1]
    names = re.findall(r"^\| `([^`]+)` \|", section, flags=re.MULTILINE)
    assert names, "no asset table found in ADR 0003"
    return [n.replace("<ver>", ver) for n in names]


def _expected_files(ver: str) -> set[str]:
    rows = _adr_assets(ver)
    assert "<asset>.sha256" in rows and "SHA256SUMS" in rows
    assets = [r for r in rows if r not in {"<asset>.sha256", "SHA256SUMS"}]
    return set(assets) | {f"{a}.sha256" for a in assets} | {"SHA256SUMS"}


@needs_tools
def test_script_asset_list_equals_the_adr_table() -> None:
    listed = subprocess.run(
        [str(SCRIPT), "--list-assets", "1.2.3-rc.1"], capture_output=True, text=True, check=True
    ).stdout.split()
    adr = [r for r in _adr_assets("1.2.3-rc.1") if r not in {"<asset>.sha256", "SHA256SUMS"}]
    assert sorted(listed) == sorted(adr)


def _fake_tree(base: Path, ver: str) -> tuple[Path, Path, Path]:
    repo = base / "repo"
    tools = repo / "contracts" / "tools"
    tools.mkdir(parents=True)
    for name in ("bundle.py", "contract_resolver.py"):
        (tools / name).write_text(f"# {name}\n")
    (tools / "pins.json").write_text("{}\n")
    (tools / "enum_pins.json").write_text("{}\n")
    (tools / "fixtures").mkdir()
    (tools / "fixtures" / "not-shipped.json").write_text("{}\n")
    svc = repo / "service"
    files = {
        "src/test/resources/conformance/case-a/golden/project.json": "{}",
        "src/test/resources/conformance/case-a/kitty-specs/m/meta.json": "{}",
        "src/test/resources/artifact-conformance/cases.json": "[]",
        "src/test/resources/artifact-conformance/golden.json": "[]",
        "src/test/resources/nativesmoke/requests.json": "[]",
        "src/main/resources/leak-patterns.json": "[]",
        "event-log/src/test/resources/torn-tail/repair-cases.json": "[]",
    }
    for rel, text in files.items():
        (svc / rel).parent.mkdir(parents=True, exist_ok=True)
        (svc / rel).write_text(text + "\n")
    bundle_out = base / "bundle-out"
    for module in ("mission-status", "mission-write"):
        (bundle_out / "bundle" / module).mkdir(parents=True)
        (bundle_out / "bundle" / module / "openapi.yaml").write_text(f"openapi: 3.1.0 # {module}\n")
    natives = base / "natives"
    natives.mkdir()
    for target in TARGETS:
        exe = ".exe" if target == "windows-x64" else ""
        (natives / f"mission-status-api-{ver}-{target}{exe}").write_bytes(target.encode())
    (natives / f"mission-status-api-{ver}.jar").write_bytes(b"jar")
    return repo, bundle_out, natives


def _run(repo: Path, bundle_out: Path, natives: Path, tag: str, out: Path) -> dict[str, str]:
    env = {
        **os.environ,
        "REPO_ROOT": str(repo),
        "BUNDLE_OUT": str(bundle_out),
        "SOURCE_DATE_EPOCH": "1761696000",
    }
    subprocess.run([str(SCRIPT), tag, str(out), str(natives)], env=env, check=True)
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.iterdir())}


@needs_tools
def test_script_builds_exactly_the_contract_set_deterministically(tmp_path: Path) -> None:
    ver = "0.1.0-rc.1"
    repo, bundle_out, natives = _fake_tree(tmp_path, ver)
    first = _run(repo, bundle_out, natives, f"service-v{ver}", tmp_path / "out1")
    # Touch every input: the mtimes must not reach the tarballs.
    for path in [*repo.rglob("*"), *bundle_out.rglob("*")]:
        os.utime(path, (1, 1))
    second = _run(repo, bundle_out, natives, f"service-v{ver}", tmp_path / "out2")
    assert set(first) == _expected_files(ver)
    assert first == second
    sums = (tmp_path / "out1" / "SHA256SUMS").read_text().splitlines()
    assert [line.split("  ", 1)[1] for line in sums] == sorted(
        n for n in first if not n.endswith(".sha256") and n != "SHA256SUMS"
    )
    tools = subprocess.run(
        ["tar", "-tzf", str(tmp_path / "out1" / f"contract-tools-{ver}.tar.gz")],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.split()
    assert "contracts/tools/fixtures/not-shipped.json" not in tools
    assert "contracts/tools/pins.json" in tools


@needs_tools
@pytest.mark.parametrize("tag", ["v1.0.0", "service-v1.0", "service-v1.0.0+build", "service-v1"])
def test_script_refuses_a_tag_outside_the_service_line(tmp_path: Path, tag: str) -> None:
    repo, bundle_out, natives = _fake_tree(tmp_path, "1.0.0")
    with pytest.raises(subprocess.CalledProcessError):
        _run(repo, bundle_out, natives, tag, tmp_path / "out")


@needs_tools
def test_script_refuses_a_missing_native(tmp_path: Path) -> None:
    repo, bundle_out, natives = _fake_tree(tmp_path, "1.0.0")
    (natives / "mission-status-api-1.0.0-macos-arm64").unlink()
    with pytest.raises(subprocess.CalledProcessError):
        _run(repo, bundle_out, natives, "service-v1.0.0", tmp_path / "out")


@pytest.mark.skipif(shutil.which("actionlint") is None, reason="actionlint is not installed")
def test_actionlint_accepts_both_workflows() -> None:
    subprocess.run(["actionlint", str(SERVICE), str(RELEASE)], check=True)
