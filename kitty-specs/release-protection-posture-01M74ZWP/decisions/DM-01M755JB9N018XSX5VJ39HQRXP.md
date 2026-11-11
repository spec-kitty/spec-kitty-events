# Decision Moment `01M755JB9N018XSX5VJ39HQRXP`

- **Mission:** `release-protection-posture-01M74ZWP`
- **Origin flow:** `specify`
- **Slot key:** `specify.canary.token-scope`
- **Input key:** `canary_token_scope`
- **Status:** `resolved`
- **Created:** `2026-11-11T11:23:24.341845+00:00`
- **Resolved:** `2026-11-11T11:23:27.110583+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

Narrow the PR-lane canary's org-wide App token in the workflow?

## Options

- Two narrow tokens
- Repositories only
- No change, accept

## Final answer

No change, accept: leave ci-pr-lane-canary.yml as is; record the org-wide App token as an accepted risk with sign-off; narrowing the App installation is an Operator action.

## Rationale

_(none)_

## Change log

- `2026-11-11T11:23:24.341845+00:00` - opened
- `2026-11-11T11:23:27.110583+00:00` - resolved (final_answer="No change, accept: leave ci-pr-lane-canary.yml as is; record the org-wide App token as an accepted risk with sign-off; narrowing the App installation is an Operator action.")
