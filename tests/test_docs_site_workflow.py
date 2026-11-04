"""The docs-site build job renders diagrams at build time (ADR-0006, issue #195).

``docs-site.yml`` builds the site in the digest-pinned Playwright container with a
digest-pinned PlantUML service (SANDBOX profile), waits for the service within the
NFR-002 bound, restores the diagram render cache, and renders Mermaid and PlantUML to
static SVG (``DK_DIAGRAM_BUILD_RENDER=build``) with a guard that fails the build rather
than silently falling back to client rendering. These tests pin the container and
service digests against the ``playwright`` pin in ``site/package.json``
(bump tag, digest and pin together) and the probe arithmetic against the 120 s bound.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

_REPO_ROOT = Path(__file__).resolve().parent.parent
_WORKFLOW = _REPO_ROOT / ".github" / "workflows" / "docs-site.yml"
_PACKAGE_JSON = _REPO_ROOT / "site" / "package.json"

# The digest the comment above the container image records (docker buildx imagetools
# inspect, resolved 2026-11-03). Bump with the tag and the site/package.json pin.
_PLAYWRIGHT_DIGEST = "dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e"
_PLANTUML_DIGEST = "065c66adc6728ba6dd353a29d4ada73f8358b68549a0f710c4bf46b43c103618"


def _load() -> dict[str, Any]:
    # BaseLoader keeps `on` a string key (safe_load turns it into True).
    data: dict[str, Any] = yaml.load(_WORKFLOW.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    return data


def _step(name: str) -> dict[str, Any]:
    steps: list[dict[str, Any]] = _load()["jobs"]["build"]["steps"]
    return next(s for s in steps if s.get("name") == name)


def _playwright_pin() -> str:
    package = json.loads(_PACKAGE_JSON.read_text(encoding="utf-8"))
    return package["devDependencies"]["playwright"]


def test_the_container_tag_is_derived_from_the_playwright_pin() -> None:
    # The container tag, its digest and the site/package.json pin move together
    # (NFR-005 traceability): the test derives the tag from the pin, never hardcodes it.
    image = _load()["jobs"]["build"]["container"]["image"]
    assert image == (
        f"mcr.microsoft.com/playwright:v{_playwright_pin()}-noble@sha256:{_PLAYWRIGHT_DIGEST}"
    )


def test_every_container_and_service_image_is_digest_pinned() -> None:
    # NFR-005: no floating tags anywhere in the workflow.
    for job_id, job in _load()["jobs"].items():
        images = [job.get("container", {}).get("image")]
        images += [s.get("image") for s in job.get("services", {}).values()]
        for image in images:
            if image is None:
                continue
            assert "@sha256:" in image, (job_id, image)


def test_the_plantuml_service_is_digest_pinned_and_sandboxed() -> None:
    plantuml = _load()["jobs"]["build"]["services"]["plantuml"]
    assert plantuml["image"] == (
        f"plantuml/plantuml-server:jetty-v1.2026.9@sha256:{_PLANTUML_DIGEST}"
    )
    # SANDBOX: a diagram cannot reach the network.
    assert plantuml["env"]["PLANTUML_SECURITY_PROFILE"] == "SANDBOX"
    # astro-plantuml renders via GET <server>/<encoded>; Jetty's default request-line
    # limit (8192) rejects the biggest schema diagrams (HTTP 414, verified 2026-11-03:
    # work_observation_payload encodes to 8236 chars), so the raised limit is pinned
    # (via _JAVA_OPTIONS: container-time JAVA_OPTIONS is ignored by the baked jetty.start).
    assert plantuml["env"]["_JAVA_OPTIONS"] == "-Djetty.httpConfig.requestHeaderSize=1048576"


def test_the_build_step_renders_build_mode_and_guards_it() -> None:
    build = _step("Build")
    assert build["env"]["DK_DIAGRAM_BUILD_RENDER"] == "build"
    # The guard fails the build rather than falling back to client rendering.
    assert "DK_DIAGRAM_BUILD_RENDER must be build" in build["run"]
    assert "::error::" in build["run"]
    assert build["env"]["DK_PLANTUML_SERVER_URL"] == "http://plantuml:8080/svg/"
    # The render cache the restore step stages at the repository root.
    assert "DK_BEOE_CACHE_DIR" in build["run"]


def test_the_diagram_cache_key_follows_the_toolkit_pin() -> None:
    cache = _step("Restore the diagram render cache")
    # Bump the 0.6.0 prefix with the @spec-kitty/doc-toolkit pin in site/package.json.
    assert cache["with"]["key"].startswith("dk-diagram-0.6.0-")
    assert cache["with"]["path"] == ".dk-diagram-cache"
    assert "dk-diagram-0.6.0-" in cache["with"]["restore-keys"]


def test_the_readiness_probe_stays_within_the_nfr_002_bound() -> None:
    # NFR-002: worst case N x (max-time + sleep) must not exceed 120 s, and a timeout
    # fails naming the service.
    run = _step("Wait for the PlantUML server")["run"]
    seq = re.search(r"seq 1 (\d+)", run)
    max_time = re.search(r"--max-time (\d+)", run)
    sleep = re.search(r"sleep (\d+)", run)
    assert seq and max_time and sleep, run
    worst_case = int(seq.group(1)) * (int(max_time.group(1)) + int(sleep.group(1)))
    assert worst_case <= 120
    assert "plantuml" in run.split("exit 1")[0].rsplit("echo", 1)[-1]


def test_install_skips_scripts_and_the_comment_pins_the_reason() -> None:
    install = _step("Install")
    assert install["run"].strip() == "npm ci --ignore-scripts"


def test_the_job_name_and_id_stay_byte_identical() -> None:
    # C-002: the ruleset waits on the `docs-build` check of job `build`.
    job = _load()["jobs"]["build"]
    assert job["name"] == "docs-build"


# --- WP04 (#195): the staged-schema ⇔ built-page + diagram gate ---------------------

_COUNT_GATE = "Check every staged schema has a built page with a rendered diagram"
_DRIFT_GATE = "Check the generated schema pages match the schemas"
_HAS_BASH = shutil.which("bash") is not None


def _run_bash_step(step_name: str, cwd: Path, tmp_path: Path) -> subprocess.CompletedProcess[str]:
    """Run a step's script as the runner would (bash -e), in the given cwd."""
    step = _step(step_name)
    script = tmp_path / "step.sh"
    script.write_text(step["run"], encoding="utf-8")
    env = {**os.environ, "GITHUB_OUTPUT": os.devnull, "GITHUB_STEP_SUMMARY": os.devnull}
    return subprocess.run(
        ["bash", "--noprofile", "--norc", "-e", str(script)],
        cwd=cwd,
        env=env,
        capture_output=True,
        text=True,
    )


def test_the_drift_check_step_runs_the_bare_venv_python_from_repo_root() -> None:
    # stdlib-only generator: the docs-venv python needs no packages; working-directory
    # '.' because the job default is site/.
    step = _step(_DRIFT_GATE)
    assert step.get("working-directory", ".") == "."
    assert '"$RUNNER_TEMP/docs-venv/bin/python" scripts/schema_pages.py --check' in step["run"]


def test_the_count_gate_pins_the_three_schematic_trees_and_the_envelope_renames() -> None:
    run = _step(_COUNT_GATE)["run"]
    assert "site/dist/events/*/*/index.html" in run
    assert "site/dist/architecture/producer-row-schemas/*/index.html" in run
    assert "site/dist/api/envelope/*/index.html" in run
    assert "event-envelope" in run and "producer-envelope-write" in run
    # The error-SVG exclusion uses the markers demonstrated against the pinned
    # PlantUML server (recorded in the step comment).
    assert "Syntax Error?" in run and "Your data does not sound like JSON data" in run


def _fixture_built_site(tmp_path: Path, stems: list[str]) -> Path:
    site = tmp_path / "site"
    (site / "schemas").mkdir(parents=True)
    for stem in stems:
        page = site / "dist" / "events" / "mission-lifecycle" / stem
        page.mkdir(parents=True)
        page.joinpath("index.html").write_text(
            '<html><figure class="dk-diagram" role="group"><svg xmlns="x"></svg></figure>',
            encoding="utf-8",
        )
    return site


@pytest.mark.skipif(not _HAS_BASH, reason="bash not available")
def test_the_count_gate_passes_when_every_staged_schema_has_a_built_page_with_a_diagram(
    tmp_path: Path,
) -> None:
    site = _fixture_built_site(tmp_path, ["alpha", "beta"])
    for stem in ("alpha", "beta"):
        (site / "schemas" / f"{stem}.schema.json").write_text("{}", encoding="utf-8")
    result = _run_bash_step(_COUNT_GATE, tmp_path, tmp_path)
    assert result.returncode == 0, result.stderr
    assert "2 built schematic pages for 2 staged schemas" in result.stderr + result.stdout


@pytest.mark.skipif(not _HAS_BASH, reason="bash not available")
def test_the_count_gate_fails_naming_a_staged_schema_without_a_built_page(
    tmp_path: Path,
) -> None:
    # Staged `gamma.schema.json` has no built page: the step must exit non-zero NAMING it.
    site = _fixture_built_site(tmp_path, ["alpha"])
    for stem in ("alpha", "gamma"):
        (site / "schemas" / f"{stem}.schema.json").write_text("{}", encoding="utf-8")
    result = _run_bash_step(_COUNT_GATE, tmp_path, tmp_path)
    assert result.returncode != 0
    assert "gamma" in result.stderr + result.stdout


@pytest.mark.skipif(not _HAS_BASH, reason="bash not available")
def test_the_count_gate_fails_naming_a_page_whose_diagram_is_an_error_svg(
    tmp_path: Path,
) -> None:
    site = _fixture_built_site(tmp_path, ["alpha"])
    broken = site / "dist" / "events" / "mission-lifecycle" / "alpha" / "index.html"
    broken.write_text(
        '<figure class="dk-diagram" x><svg>@Syntax Error?@</svg></figure>', encoding="utf-8"
    )
    (site / "schemas" / "alpha.schema.json").write_text("{}", encoding="utf-8")
    result = _run_bash_step(_COUNT_GATE, tmp_path, tmp_path)
    assert result.returncode != 0
    assert "alpha/index.html" in result.stderr + result.stdout
