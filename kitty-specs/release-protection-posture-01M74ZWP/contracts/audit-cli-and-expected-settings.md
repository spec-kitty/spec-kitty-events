# Contract: release-protection audit CLI and expected-settings file

## CLI

```
python3 scripts/release/release_protection_audit.py \
  --repo <owner>/<name> \
  --expected scripts/release/release-protection-expected.json \
  [--summary <path>]          # appends a Markdown report (the workflow passes $GITHUB_STEP_SUMMARY)
  [--skip-releases]           # settings only (local use)
  [--skip-settings]           # releases only (local use)
```

- Reads through `gh` (the caller's `GH_TOKEN`); never writes to GitHub.
- Prints one line per finding: `FAIL settings service_release.reviewers: expected [...], live [...]`,
  `WARN release service-v1.1.0-rc.1/<asset>: attestation verification failed: <reason>`.
- Exit codes: 0 no failures, 1 failures, 2 error (data-model.md).

## Expected-settings file

`scripts/release/release-protection-expected.json`: the normalised settings shape in data-model.md, pretty-printed with
sorted keys and a trailing newline. A deliberate settings change lands as a reviewed PR that edits this file; the
audit's red run until then is the intended signal.

## Workflow

`.github/workflows/release-protection-audit.yml`: `on: schedule` + `workflow_dispatch` only; `permissions: contents: read`
at workflow level and no job-level `permissions`; one job, `timeout-minutes: 30`; every `uses:` a 40-hex SHA with a
version comment.
