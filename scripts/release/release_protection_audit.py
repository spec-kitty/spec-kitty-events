#!/usr/bin/env python3
"""Audit the release-protection posture of a repository (read-only).

Two checks, both driven through the ``gh`` CLI with the caller's ``GH_TOKEN``:

1. Settings drift: the live ``service-release`` environment settings, the
   repository environments and the repository rulesets are normalised and
   diffed against the committed ``release-protection-expected.json``.
   Bypass lists (``bypass_actors``) are never recorded or compared (D6): a
   read-only token cannot see them, so comparing them would make the audit
   depend on the token's privileges.
2. Release provenance: every ``service-v*`` release must be authored by
   ``github-actions[bot]`` and every non-checksum asset must carry a build
   attestation signed by the ``service-release.yml`` workflow on the release
   tag. A prerelease (tag version with a ``-`` suffix) reports its faults as
   warnings instead of failures (D8); infrastructure faults are never
   downgraded.

Exit codes: 0 no failures (warnings allowed), 1 at least one failure, 2 the
audit could not complete (API fault) or an asset could not be verified (an
"error" finding; the remaining assets and releases are still checked). Text
the attacker controls (quoted values, asset and tag names) is stripped before
gh's stderr is classified; anything not an explicit verification outcome is an
infrastructure fault, never a verdict.

The script is read-only: it never writes to GitHub.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, NamedTuple, Sequence

SERVICE_TAG = re.compile(r"^service-v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$")
BOT = "github-actions[bot]"
ENVIRONMENT = "service-release"
SIGNER_WORKFLOW = ".github/workflows/service-release.yml"
CHUNK = 1024 * 1024
DUPLICATE_KEY = "duplicate_ruleset_name"

# `gh attestation verify` stderr classification (FR-010): only these are verdicts.
_VERDICT_MARKERS = (
    "policy verification failed",
    "no matching certificateidentity",
    "no attestations found",
    "artifact does not match digest",
    "failed to verify signature",
    "failed to verify log inclusion",
)
_EXPECTED_TOPICS = ("san", "signer", "source", "predicate", "runner")

FAILURE = "failure"
WARNING = "warning"
ERROR = "error"


class AuditError(Exception):
    """An infrastructure fault: the audit could not complete (exit code 2)."""


class AssetFault(AuditError):
    """An infrastructure fault confined to one asset: reported, and the audit goes on."""


@dataclass(frozen=True)
class Finding:
    """One audit result."""

    severity: str  # "failure" | "warning"
    kind: str  # "settings" | "release"
    subject: str
    message: str


class AssetVerdict(NamedTuple):
    ok: bool
    reason: str  # empty when ok


AssetVerifier = Callable[[str, dict[str, Any]], AssetVerdict]
Runner = Callable[[list[str]], "subprocess.CompletedProcess[str]"]


def _run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, check=False)


# ---------------------------------------------------------------------------
# Normalisation and diff (pure)
# ---------------------------------------------------------------------------


def _canon_text(value: Any) -> str:
    return json.dumps(value, sort_keys=True)


def _sorted_lists(value: Any) -> Any:
    """Recursively sort every list by canonical JSON text."""
    if isinstance(value, dict):
        return {k: _sorted_lists(v) for k, v in value.items()}
    if isinstance(value, list):
        return sorted((_sorted_lists(v) for v in value), key=_canon_text)
    return value


def _normalise_ruleset(ruleset: dict[str, Any]) -> dict[str, Any]:
    rules = [_sorted_lists(rule) for rule in ruleset.get("rules") or []]
    rules.sort(key=lambda r: (str(r.get("type", "")), _canon_text(r)))
    return {
        "target": ruleset.get("target"),
        "enforcement": ruleset.get("enforcement"),
        "conditions": _sorted_lists(ruleset.get("conditions") or {}),
        "rules": rules,
    }


def _reviewer_label(entry: dict[str, Any]) -> str:
    kind = str(entry.get("type", ""))
    who = entry.get("reviewer") or {}
    name = who.get("slug") if kind == "Team" else who.get("login")
    return f"{kind}:{name}"


def _normalise_environment(
    service_release: dict[str, Any], branch_policies: dict[str, Any]
) -> dict[str, Any]:
    rules = service_release.get("protection_rules") or []
    reviewers: list[str] = []
    prevent_self_review = False
    wait_timer = 0
    for rule in rules:
        if rule.get("type") == "required_reviewers":
            prevent_self_review = bool(rule.get("prevent_self_review", False))
            reviewers.extend(_reviewer_label(r) for r in rule.get("reviewers") or [])
        elif rule.get("type") == "wait_timer":
            wait_timer = int(rule.get("wait_timer") or 0)
    policies = [
        {"name": p.get("name"), "type": p.get("type")}
        for p in branch_policies.get("branch_policies") or []
    ]
    return {
        "can_admins_bypass": service_release.get("can_admins_bypass"),
        "deployment_branch_policy": service_release.get("deployment_branch_policy"),
        "deployment_policies": sorted(policies, key=_canon_text),
        "protection_rules": sorted(str(r.get("type")) for r in rules),
        "prevent_self_review": prevent_self_review,
        "reviewers": sorted(reviewers),
        "wait_timer": wait_timer,
    }


def _normalise_rulesets(rulesets: list[dict[str, Any]]) -> dict[str, Any]:
    names = [str(rs.get("name")) for rs in rulesets]
    out: dict[str, Any] = {}
    for name, rs in zip(names, rulesets):
        # A repeated name (e.g. an inherited org ruleset) is flagged, never collapsed.
        out[name] = (
            {DUPLICATE_KEY: names.count(name)} if names.count(name) > 1 else _normalise_ruleset(rs)
        )
    return out


def normalise_settings(
    repository: str,
    environments: dict[str, Any],
    service_release: dict[str, Any],
    branch_policies: dict[str, Any],
    rulesets: list[dict[str, Any]],
) -> dict[str, Any]:
    """Reduce raw GitHub API responses to the comparable settings shape."""
    return {
        "repository": repository,
        "environments": sorted(str(e.get("name")) for e in environments.get("environments") or []),
        "service_release": _normalise_environment(service_release, branch_policies),
        "rulesets": _normalise_rulesets(rulesets),
    }


def _show(value: Any) -> str:
    return _canon_text(value)


def _walk(path: str, expected: Any, live: Any, out: list[Finding]) -> None:
    if isinstance(live, dict) and DUPLICATE_KEY in live:
        out.append(Finding(FAILURE, "settings", path, "duplicate ruleset name"))
        return
    if isinstance(expected, dict) and isinstance(live, dict):
        for key in sorted(set(expected) | set(live)):
            sub = f"{path}.{key}" if path else str(key)
            if key not in live:
                out.append(Finding(FAILURE, "settings", sub, "missing live"))
            elif key not in expected:
                out.append(
                    Finding(FAILURE, "settings", sub, f"unexpected live value {_show(live[key])}")
                )
            else:
                _walk(sub, expected[key], live[key], out)
        return
    if expected != live:
        out.append(
            Finding(
                FAILURE,
                "settings",
                path,
                f"expected {_show(expected)}, live {_show(live)}",
            )
        )


def diff_settings(expected: dict[str, Any], live: dict[str, Any]) -> list[Finding]:
    """Diff normalised settings; the result is sorted by path (deterministic)."""
    out: list[Finding] = []
    _walk("", expected, live, out)
    return sorted(out, key=lambda f: (f.subject, f.message))


# ---------------------------------------------------------------------------
# Release provenance (pure, injected verifier)
# ---------------------------------------------------------------------------


def _is_checksum(name: str) -> bool:
    return name == "SHA256SUMS" or name.endswith(".sha256")


def audit_releases(releases: list[dict[str, Any]], verify: AssetVerifier) -> list[Finding]:
    """Check release authorship and per-asset attestation for service-v* tags."""
    findings: list[Finding] = []
    for release in sorted(releases, key=lambda r: str(r.get("tag_name", ""))):
        tag = str(release.get("tag_name", ""))
        if not tag.startswith("service-v"):
            continue
        if not SERVICE_TAG.fullmatch(tag):
            findings.append(
                Finding(FAILURE, "release", tag, "service-v* tag outside the release line")
            )
            continue
        severity = WARNING if "-" in tag[len("service-v") :] else FAILURE

        def fault(subject: str, message: str, _severity: str = severity) -> None:
            findings.append(Finding(_severity, "release", subject, message))

        login = (release.get("author") or {}).get("login")
        if login != BOT:
            fault(tag, f"authored by {login}, not {BOT}")
        assets = sorted(release.get("assets") or [], key=lambda a: str(a.get("name", "")))
        if not any(not _is_checksum(str(a.get("name", ""))) for a in assets):
            fault(tag, "no non-checksum assets")
        for asset in assets:
            name = str(asset.get("name", ""))
            if _is_checksum(name):
                continue
            try:
                verdict = verify(tag, asset)
            except AssetFault as exc:
                # never downgraded on a prerelease, and the loop continues
                findings.append(
                    Finding(ERROR, "release", f"{tag}/{name}", f"could not verify: {exc}")
                )
                continue
            if not verdict.ok:
                fault(f"{tag}/{name}", verdict.reason)
    return findings


# ---------------------------------------------------------------------------
# gh adapter
# ---------------------------------------------------------------------------


def _gh(runner: Runner, args: list[str], path: str) -> str:
    result = runner(["gh", *args])
    if result.returncode != 0:
        detail = (result.stderr or "").strip().splitlines()
        raise AuditError(f"gh api {path} failed: {detail[-1] if detail else 'no output'}")
    return result.stdout


def gh_json(runner: Runner, path: str, *, paginate: bool = False) -> Any:
    """GET ``path``; paginated list endpoints are flattened across pages."""
    args = ["api", "--paginate", "--slurp", path] if paginate else ["api", path]
    stdout = _gh(runner, args, path)
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise AuditError(f"gh api {path} returned invalid JSON: {exc}") from exc
    if not paginate:
        return data
    if not isinstance(data, list):
        raise AuditError(f"gh api {path} --slurp did not return a list of pages")
    return data


def _flatten_pages(pages: list[Any], key: str | None = None) -> list[Any]:
    items: list[Any] = []
    for page in pages:
        if isinstance(page, list):
            items.extend(page)
        elif isinstance(page, dict) and key is not None:
            items.extend(page.get(key) or [])
        else:
            raise AuditError("unexpected page shape in paginated response")
    return items


def read_live_settings(runner: Runner, repo: str) -> dict[str, Any]:
    """Read and normalise the live release-protection settings."""
    base = f"repos/{repo}"
    listed = _flatten_pages(gh_json(runner, f"{base}/rulesets", paginate=True))
    rulesets = []
    for rs in listed:
        if rs.get("id") is None:
            raise AuditError(f"ruleset {rs.get('name')!r} has no id in the rulesets listing")
        rulesets.append(gh_json(runner, f"{base}/rulesets/{rs['id']}"))
    env_pages = gh_json(runner, f"{base}/environments", paginate=True)
    environments = {"environments": _flatten_pages(env_pages, "environments")}
    names = {e.get("name") for e in environments["environments"]}
    service_release: dict[str, Any] = {}
    policies: dict[str, Any] = {"branch_policies": []}
    if ENVIRONMENT in names:
        env_path = f"{base}/environments/{ENVIRONMENT}"
        service_release = gh_json(runner, env_path)
        pol_pages = gh_json(runner, f"{env_path}/deployment-branch-policies", paginate=True)
        policies = {"branch_policies": _flatten_pages(pol_pages, "branch_policies")}
    return normalise_settings(repo, environments, service_release, policies, rulesets)


def read_releases(runner: Runner, repo: str) -> list[dict[str, Any]]:
    pages = gh_json(runner, f"repos/{repo}/releases?per_page=100", paginate=True)
    return [r for r in _flatten_pages(pages) if isinstance(r, dict)]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sanitise(stderr: str, scrub: Sequence[str]) -> str:
    """Drop text an attacker controls: quoted values, file paths, asset and tag names."""
    text = re.sub(r'"[^"]*"', " ", stderr)
    for needle in sorted({n for n in scrub if n}, key=len, reverse=True):
        # Whole tokens only, so a short asset name such as `e` cannot erase letters of a marker.
        text = re.sub(rf"(?<![\w./-]){re.escape(needle)}(?![\w./-])", " ", text)
    return text.lower()


def _is_verification_outcome(stderr: str, scrub: Sequence[str] = ()) -> bool:
    """True only for a known verification outcome; anything else is infrastructure."""
    text = _sanitise(stderr, scrub)
    if any(marker in text for marker in _VERDICT_MARKERS):
        return True
    # Word-bounded: `unexpected` and `resource` (in gh's own fault text) must not read as a verdict.
    if re.search(r"\bexpected\b", text) and any(
        re.search(rf"\b{topic}", text) for topic in _EXPECTED_TOPICS
    ):
        return True
    # Unrecognised text (HTTP errors, timeouts, DNS/TLS faults, empty stderr) is infrastructure.
    return False


def _raise_infra(what: str, stderr: str | None) -> None:
    lines = [ln.strip() for ln in (stderr or "").strip().splitlines() if ln.strip()]
    raise AssetFault(f"{what} failed: {lines[-1] if lines else 'no output'}")


def gh_asset_verifier(runner: Runner, repo: str, workdir: Path) -> AssetVerifier:
    """Production verifier: download, check digest, verify attestation, delete."""

    def verify(tag: str, asset: dict[str, Any]) -> AssetVerdict:
        name = str(asset["name"])
        target = workdir / name
        try:
            download = runner(
                [
                    "gh",
                    "release",
                    "download",
                    tag,
                    "--repo",
                    repo,
                    "--pattern",
                    name,
                    "--dir",
                    str(workdir),
                    "--clobber",
                ]
            )
            if download.returncode != 0 or not target.is_file():
                detail = (download.stderr or "").strip().splitlines()
                raise AssetFault(
                    f"download of {tag}/{name} failed: {detail[-1] if detail else 'no file'}"
                )
            actual = _sha256(target)
            api_digest = asset.get("digest")
            if api_digest and str(api_digest).removeprefix("sha256:").lower() != actual:
                return AssetVerdict(
                    False,
                    f"digest mismatch: api {str(api_digest).removeprefix('sha256:')}, file {actual}",
                )
            lookup_path = f"repos/{repo}/attestations/sha256:{actual}"
            lookup = runner(["gh", "api", lookup_path])
            if lookup.returncode != 0:
                if re.search(r"HTTP 404", lookup.stderr or "", re.IGNORECASE):
                    return AssetVerdict(False, f"no attestation for sha256:{actual}")
                _raise_infra(f"attestation lookup {lookup_path}", lookup.stderr)
            try:
                found = json.loads(lookup.stdout).get("attestations")
            except (json.JSONDecodeError, AttributeError) as exc:
                raise AssetFault(f"attestation lookup {lookup_path}: unexpected response") from exc
            if not found:
                return AssetVerdict(False, f"no attestation for sha256:{actual}")
            attest = runner(
                [
                    "gh",
                    "attestation",
                    "verify",
                    str(target),
                    "--repo",
                    repo,
                    "--cert-identity",
                    f"https://github.com/{repo}/{SIGNER_WORKFLOW}@refs/tags/{tag}",
                    "--source-ref",
                    f"refs/tags/{tag}",
                    "--deny-self-hosted-runners",
                ]
            )
            if attest.returncode != 0:
                stderr = attest.stderr or ""
                if not _is_verification_outcome(stderr, (str(target), name, tag)):
                    _raise_infra(f"attestation verify of {tag}/{name}", stderr)
                lines = [ln for ln in stderr.strip().splitlines() if ln.strip()]
                return AssetVerdict(False, f"attestation verification failed: {lines[-1].strip()}")
            return AssetVerdict(True, "")
        finally:
            target.unlink(missing_ok=True)

    return verify


# ---------------------------------------------------------------------------
# Reporting and CLI
# ---------------------------------------------------------------------------


_LABELS = {FAILURE: "FAIL", WARNING: "WARN", ERROR: "ERROR"}


def render_summary(findings: list[Finding], error: str | None = None) -> str:
    failures = sum(1 for f in findings if f.severity == FAILURE)
    warnings = sum(1 for f in findings if f.severity == WARNING)
    errors = sum(1 for f in findings if f.severity == ERROR)
    lines = [
        "## Release-protection audit",
        "",
        f"Failures: {failures}, warnings: {warnings}, errors: {errors}",
        "",
    ]
    if error is not None:
        lines.append(f"ERROR: {error}")
    elif not findings:
        lines.append("No findings.")
    for f in findings:
        label = _LABELS[f.severity]
        lines.append(f"- **{label}** {f.kind} `{f.subject}`: {f.message}")
    return "\n".join(lines) + "\n"


def _line(f: Finding) -> str:
    label = _LABELS[f.severity]
    return f"{label} {f.kind} {f.subject}: {f.message}"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--repo", required=True, help="<owner>/<name>")
    parser.add_argument("--expected", required=True, type=Path, help="expected settings JSON")
    parser.add_argument("--summary", type=Path, help="append a Markdown report to this file")
    parser.add_argument("--skip-settings", action="store_true")
    parser.add_argument("--skip-releases", action="store_true")
    return parser


def _collect(args: argparse.Namespace, runner: Runner, findings: list[Finding]) -> None:
    if not args.skip_settings:
        try:
            expected = json.loads(args.expected.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise AuditError(f"cannot load expected settings {args.expected}: {exc}") from exc
        findings.extend(diff_settings(expected, read_live_settings(runner, args.repo)))
    if not args.skip_releases:
        releases = read_releases(runner, args.repo)
        with tempfile.TemporaryDirectory() as tmp:
            verifier = gh_asset_verifier(runner, args.repo, Path(tmp))
            findings.extend(audit_releases(releases, verifier))


def main(argv: list[str] | None = None, runner: Runner = _run) -> int:
    args = _parser().parse_args(argv)
    findings: list[Finding] = []
    error: str | None = None
    try:
        _collect(args, runner, findings)
    except AuditError as exc:
        error = str(exc)
    except Exception as exc:  # noqa: BLE001 - any unexpected fault is an error, never a pass
        error = f"unexpected {type(exc).__name__}: {exc}"
    for finding in findings:
        print(_line(finding))
    if error is not None:
        print(f"ERROR {error}")
    if args.summary is not None:
        with args.summary.open("a", encoding="utf-8") as handle:
            handle.write(render_summary(findings, error))
    if error is not None:
        return 2
    if any(f.severity == FAILURE for f in findings):
        return 1
    return 2 if any(f.severity == ERROR for f in findings) else 0


if __name__ == "__main__":
    sys.exit(main())
