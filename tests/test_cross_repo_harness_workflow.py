"""Credential lifetime and least-privilege boundaries for the private harness checkout."""

import json
import os
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
