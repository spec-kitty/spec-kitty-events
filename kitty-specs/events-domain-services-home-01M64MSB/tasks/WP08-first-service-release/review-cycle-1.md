---
affected_files: []
cycle_number: 1
mission_slug: events-domain-services-home-01M64MSB
reproduction_command: spec-kitty agent tasks move-task WP08 --to approved --mission events-domain-services-home-01M64MSB --agent claude
reviewed_at: '2026-11-04T19:13:22Z'
reviewer_agent: claude
wp_id: WP08
---

**No review ran for this WP. This is a retrospective closeout record (#205), not an independent approval.**

WP08 ran outside the WP loop and moved `planned -> done` with a forced override, so `reproduction_command` above is the tooling's default, not what happened. `reviewer_agent` is the closeout session.

What the record shows:

- `service-v0.1.0-rc.1` (`2f5e0394`, operator approval on core #6117) and `service-v0.1.0-rc.2` (`14682f94`) are published. rc.2's publication and verification are recorded in #174.
- **T036 was only partly done.** Core PR #7004 records that `gh attestation verify` could not run for rc.1 in its sandbox; the GitHub API lists an attestation for the linux-x64 digest (follow-up #207). The per-release note was replaced by the CHANGELOG release notes (#174).
- **Native binary sizes (T036), from the release API, added at closeout. All are under 100 MB:**

| Asset | linux-x64 | linux-arm64 | macos-arm64 | windows-x64 |
|---|---|---|---|---|
| `mission-status-api` (rc.1, rc.2) | 95815944 | 92670072 | 89759152 | 94330880 |
| `command-cli` (rc.2) | 94177544 | 91031672 | 87659976 | 92770304 |

- **"Split per IssueMove" is a deviation.** The runbook says 8 issues transfer; they were re-filed here (#155-#170) and the originals closed `not_planned`. All 14 rows have a live destination. No approval for the change was found (follow-up #207).

The status log's reason for this WP's event says core pins rc.1 via #6915 and that session B was handed rc.2. The pin landed via core PR #7004 (#6915 is the open tracker), and the public record of the rc.2 sums is the #6915 comment of 2026-11-01.
