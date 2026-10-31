---
affected_files: []
cycle_number: 1
mission_slug: command-cli-write-service-01M694HJ
reproduction_command:
reviewed_at: '2026-10-31T15:46:33Z'
reviewer_agent: claude:sonnet:reviewer-renata
wp_id: WP07
---

# WP07 review 1: rejected for stale wording and two wrong claims

Contract tools, bundle and `:contracts:check :command-cli:check` are green. The command-cli README matches the code (exit codes, order of checks, 5 s drain, 1800 s idle, token, state files). The defects are in the docs and the changelog.

## Blocking

1. `docs/architecture/services/overview.md` still calls the write service future or unbuilt (T038 names this file):
   - line 3 (frontmatter description): "the future write service";
   - line 49: "(one per repository, future)";
   - line 53: "loopback HTTP + bearer token (future)";
   - line 104: the `command-cli` row says "**Not built.**" (the C4 diagram at ~line 86 was updated, the table and context diagram were not).
   Say it is built and runnable, and that writes refuse fail-closed until #160/#161. Keep the description at most 180 characters.
2. `docs/architecture/services/read-write-split.md:77`: "**Status: the write core is not built.**" followed by a list of what exists that omits the built `command-cli` service. T038 names this file. Rewrite the status: the service is built, the canonical writer and lane authority (#160/#161) are not. Bump `updated` (line 5, still 2026-10-30).
3. `docs/guides/service-protocol-and-releases.md:95-97`: "Two `503` codes are provisional: `SERVICE_BUSY` ... `SERVICE_DRAINING`". `SERVICE_DRAINING` was settled in this WP (contracts/mission-write/CHANGELOG.md:64, WriteRefusalCode.yaml:66). Only `SERVICE_BUSY` stays provisional. The refusal table above it also lacks the draining row (`503 SERVICE_DRAINING`, no Retry-After) between the 413 and 400 rows.
4. `service/command-cli/README.md:73-80` and the how-core-binds section cite `events.py:308` for `bind_event_bridge`. In the core clone (d75e0bc5fe) the def is at line 304. Fix the line, or cite the symbol without a line.
5. `CHANGELOG.md:17-28` states in the present tense that the native image is built and smoked on four targets (`service.yml builds and smokes the native binary on the four targets`). GraalVM native-image is not installed locally (command-cli-research.md:126-127: "native builds are verified in CI only"), so nothing proves it yet. Word it as what the workflow does once CI runs (for example "`service.yml` is set up to build and smoke ..."), and say the native image is not yet verified.

## Minor (fix in the same pass if cheap)

- `service/command-cli/README.md` "Resolve" item 4: "the jar on Java 25" is wider than core's rule (`resolution.py` docstring: only when there is no native build for the host, or the native one is unavailable and the jar is already cached). Say so.
- `docs/guides/service-cutover-runbook.md:328` still says "keep `/shutdown` marked provisional". It is a historical step; add "(until #156; settled 2026-10-31)" or leave it, but it reads as current guidance.
- ADR 0003 lines 129-130 ("Future deployable") are fixed on the WP06 lane (ADR 0003 line 134 there), not here. Make sure the merge keeps that version.
