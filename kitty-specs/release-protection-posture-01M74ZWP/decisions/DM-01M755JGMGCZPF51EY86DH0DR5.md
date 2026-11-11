# Decision Moment `01M755JGMGCZPF51EY86DH0DR5`

- **Mission:** `release-protection-posture-01M74ZWP`
- **Origin flow:** `specify`
- **Slot key:** `specify.audit.prereleases`
- **Input key:** `prerelease_rule`
- **Status:** `resolved`
- **Created:** `2026-11-11T11:23:29.808405+00:00`
- **Resolved:** `2026-11-11T11:23:32.788602+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

Rule for a service-v* prerelease that is not bot-authored or has an unattested asset?

## Options

- Warn only for prereleases
- Fail on all service-v*
- Skip prereleases

## Final answer

Warn only for prereleases: final releases fail on non-bot author or an unattested non-checksum asset; prereleases get the same checks reported as warnings; rehearsals publish no release.

## Rationale

_(none)_

## Change log

- `2026-11-11T11:23:29.808405+00:00` - opened
- `2026-11-11T11:23:32.788602+00:00` - resolved (final_answer="Warn only for prereleases: final releases fail on non-bot author or an unattested non-checksum asset; prereleases get the same checks reported as warnings; rehearsals publish no release.")
