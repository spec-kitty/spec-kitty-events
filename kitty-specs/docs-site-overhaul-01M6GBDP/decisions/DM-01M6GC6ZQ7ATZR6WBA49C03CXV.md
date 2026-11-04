# Decision Moment `01M6GC6ZQ7ATZR6WBA49C03CXV`

- **Mission:** `docs-site-overhaul-01M6GBDP`
- **Origin flow:** `plan`
- **Slot key:** `plan.environment.github-packages-auth`
- **Input key:** `github_packages_auth`
- **Status:** `resolved`
- **Created:** `2026-11-03T09:35:28.999063+00:00`
- **Resolved:** `2026-11-03T09:45:47.793280+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

Local site builds (npm ci, lockfile regeneration) need a GitHub Packages token with read:packages in ~/.npmrc; none exists on this machine (gh token lacks the scope). How do we get one?

## Options

- refresh gh auth with read:packages scope and write it to ~/.npmrc
- I will add a fine-grained PAT with read:packages to ~/.npmrc myself
- defer: implement anyway, do local site verification after the token exists
- Other

## Final answer

Refresh gh auth with the read:packages scope and write the token into user-level ~/.npmrc (operator runs the browser dance; agent prints commands). Plan and quickstart record it; WP01 verifies the auth before regenerating the lockfile.

## Rationale

_(none)_

## Change log

- `2026-11-03T09:35:28.999063+00:00` - opened
- `2026-11-03T09:45:47.793280+00:00` - resolved (final_answer="Refresh gh auth with the read:packages scope and write the token into user-level ~/.npmrc (operator runs the browser dance; agent prints commands). Plan and quickstart record it; WP01 verifies the auth before regenerating the lockfile.")
