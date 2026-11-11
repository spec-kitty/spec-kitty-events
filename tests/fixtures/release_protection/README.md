# Release-protection audit fixtures

Offline fixtures for `tests/test_release_protection_audit.py`. They are raw responses captured from the
GitHub REST API on 2026-11-11 for `spec-kitty/spec-kitty-events`.

| File (under `live/`) | Endpoint |
|---|---|
| `environments.json` | `GET /repos/{repo}/environments` |
| `env_service_release.json` | `GET /repos/{repo}/environments/service-release` (reviewer objects lightly trimmed) |
| `deployment_branch_policies.json` | `GET /repos/{repo}/environments/service-release/deployment-branch-policies` |
| `rulesets_list.json` | `GET /repos/{repo}/rulesets` |
| `rs_main.json`, `rs_tags.json` | `GET /repos/{repo}/rulesets/{id}` |
| `releases.json` | `GET /repos/{repo}/releases?per_page=100`, reduced to `tag_name`, `prerelease`, `draft`, `author.login` and per asset `name`, `size`, `digest`, `uploader.login` |

The settings are what a `contents: read` `GITHUB_TOKEN` sees (spike runs 42997528378 and 42997732131): the
rulesets carry `bypass_actors: null`. The audit never records bypass lists, so an admin token's richer
response does not change the result.

## Refreshing

```bash
R=spec-kitty/spec-kitty-events
gh api repos/$R/environments > live/environments.json
gh api repos/$R/environments/service-release > live/env_service_release.json
gh api repos/$R/environments/service-release/deployment-branch-policies > live/deployment_branch_policies.json
gh api repos/$R/rulesets > live/rulesets_list.json
gh api repos/$R/rulesets/<main-id> > live/rs_main.json
gh api repos/$R/rulesets/<tags-id> > live/rs_tags.json
gh api "repos/$R/releases?per_page=100" > /tmp/releases-raw.json   # then reduce as described above
```

If an admin token returns `bypass_actors`, remove that key from the ruleset files. Regenerate
`scripts/release/release-protection-expected.json` from the refreshed files only through
`normalise_settings`; never edit it by hand. The fixtures contain no token or secret.
