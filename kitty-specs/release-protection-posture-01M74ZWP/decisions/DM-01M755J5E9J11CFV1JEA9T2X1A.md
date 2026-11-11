# Decision Moment `01M755J5E9J11CFV1JEA9T2X1A`

- **Mission:** `release-protection-posture-01M74ZWP`
- **Origin flow:** `specify`
- **Slot key:** `specify.audit.bypass-actors`
- **Input key:** `bypass_actor_coverage`
- **Status:** `resolved`
- **Created:** `2026-11-11T11:23:18.345310+00:00`
- **Resolved:** `2026-11-11T11:23:21.428944+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

Read-only GITHUB_TOKEN gets bypass_actors=null for both rulesets. Should the audit cover ruleset bypass lists?

## Options

- Accept the blind spot
- Add an admin-read credential

## Final answer

Accept the blind spot: audit stays on contents: read; guide records bypass lists as an accepted risk with operator sign-off and a manual read-only check command.

## Rationale

_(none)_

## Change log

- `2026-11-11T11:23:18.345310+00:00` - opened
- `2026-11-11T11:23:21.428944+00:00` - resolved (final_answer="Accept the blind spot: audit stays on contents: read; guide records bypass lists as an accepted risk with operator sign-off and a manual read-only check command.")
