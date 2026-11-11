# Data Model: Release-protection audit

## Normalised settings (the expected-settings file has exactly this shape)

```json
{
  "repository": "spec-kitty/spec-kitty-events",
  "environments": ["github-pages", "pypi", "service-release", "testpypi"],
  "service_release": {
    "can_admins_bypass": false,
    "deployment_branch_policy": {"custom_branch_policies": true, "protected_branches": false},
    "deployment_policies": [{"name": "service-v*", "type": "tag"}],
    "protection_rules": ["branch_policy", "required_reviewers"],
    "prevent_self_review": true,
    "reviewers": ["Team:admins", "User:stijn-dejongh"],
    "wait_timer": 0
  },
  "rulesets": {
    "main": {"target": "branch", "enforcement": "active", "conditions": {…}, "rules": [ … sorted by type … ]},
    "service-release-tags": {"target": "tag", "enforcement": "active", "conditions": {…}, "rules": [ … ]}
  }
}
```

Rules:

- `environments`: sorted names; an added or removed environment is drift.
- `protection_rules`: sorted rule types; `wait_timer` is 0 when no `wait_timer` rule exists.
- `reviewers`: sorted `User:<login>` / `Team:<slug>`.
- `rulesets`: keyed by name; a ruleset added, removed or renamed is drift. `bypass_actors` is never recorded (D6).
- Lists inside `conditions` and rule `parameters` are sorted by their canonical JSON text.

## Finding

| Field | Values |
|---|---|
| `severity` | `failure` · `warning` (a prerelease's release fault) |
| `kind` | `settings` · `release` |
| `subject` | settings path (`service_release.reviewers`) or `<tag>` / `<tag>/<asset>` |
| `message` | one line: what was expected, what was found |

An **error** (API/download fault) is not a finding: it aborts the run with exit code 2.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | no failures (warnings allowed) |
| 1 | at least one failure |
| 2 | error: the audit could not complete |

## Release classification

- `service-v*` tag matching `^service-v\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$` (the `gate` job's rule): audited. A `-` suffix makes it a prerelease (same rule as `gate`), regardless of the release's `prerelease` flag. Any other `service-v*` tag is reported as a failure (a tag outside the release line).
- Any other tag: ignored.
- Checksum assets: `SHA256SUMS` and names ending in `.sha256`, not attestation-checked.
