# Quickstart: the release-protection audit

**Run it by hand** (after changing a setting or publishing a release):
Actions → release-protection-audit → Run workflow, or `gh workflow run release-protection-audit.yml`.

**Run it locally** (read-only; needs a `gh` login that can read the repository):

```bash
python3 scripts/release/release_protection_audit.py --repo spec-kitty/spec-kitty-events \
  --expected scripts/release/release-protection-expected.json --skip-releases
```

Note: an admin's token also sees `bypass_actors`; the audit ignores that field either way (D6).

**Read a red run**: the run summary lists each `FAIL` line.
- `settings …`: a live setting differs from the expected file. If the change was deliberate, open a PR that updates
  `scripts/release/release-protection-expected.json`; otherwise restore the setting.
- `release <tag>…`: a final release that `publish` did not create, or an asset without a valid attestation. Treat the
  release as untrusted; tell consumers not to pin it.
- Exit code 2 (error): the audit could not finish (API or network); re-run it.

**Run the tests**: `uv run pytest tests/test_release_protection_audit.py`.
