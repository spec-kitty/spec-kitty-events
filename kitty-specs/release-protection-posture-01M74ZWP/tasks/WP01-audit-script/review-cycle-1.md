---
affected_files: []
cycle_number: 1
mission_slug: release-protection-posture-01M74ZWP
reproduction_command:
reviewed_at: '2026-11-11T11:39:35Z'
reviewer_agent: reviewer-renata
wp_id: WP01
---

# WP01 review, cycle 1: REJECT (independent reviewer)

## [MAJOR] R1 — attestation infrastructure faults become verdicts (FR-010)
release_protection_audit.py: any non-zero `gh attestation verify` is an AssetVerdict(False). An API outage / 5xx / 429 / auth / TUF fetch failure during the daily run turns every rc asset into a WARN and the run exits 0 (green on an infrastructure error); on a final release it reads as a provenance failure (exit 1) instead of an error (exit 2).
Fix (fail closed):
- Before verifying, look the attestation up by digest: `gh api repos/<repo>/attestations/sha256:<hex>` (use the file's computed sha256). HTTP 404 → AssetVerdict(False, "no attestation for sha256:<hex>"). Any other non-zero → AuditError. (A 200 with zero attestations → verdict "no attestation".)
- Then `gh attestation verify ...` as now. Non-zero: classify by stderr. Only known verification outcomes become a verdict: stderr (case-insensitive) contains one of "verification failed", "no matching", "failed to verify", "no attestations found", "expected" together with "san"/"signer"/"source"/"predicate". Everything else (HTTP 4xx/5xx, "rate limit", "timeout", "connection", "tls", "401", "403", "500"-"504", "429", empty stderr) → AuditError.
- Tests: prerelease asset, attest rc=1 with stderr "HTTP 503: Service Unavailable" → main exit 2 and an ERROR line; rc=1 with a signer-mismatch stderr → WARN (prerelease) / FAIL (final); attestation lookup 404 → verdict; lookup 500 → exit 2.

## Minors folded into this cycle
- R2: a release whose only assets are checksum files (or that has no non-checksum asset) → the "no assets" fault ("no non-checksum assets").
- R3: main(): catch any unexpected Exception as an error (exit 2); print the findings collected before the error; always write the summary, with an "ERROR: <message>" line when the run errored. Also guard `wait_timer: null` (treat as 0) and missing ruleset `id` (AuditError with a message).
- R4: test multi-page flattening: serve two pages for releases, rulesets and environments, with a service-v release and the service-release environment on page 2; assert they are audited.
- R5: two rulesets with the same name → a failure finding (`rulesets.<name>: duplicate ruleset name`), not a silent collapse. (GET /rulesets defaults includes_parents=true; org rulesets can appear.)
- R6: extend the every-leaf test into lists of dicts (rule parameters, status checks): mutate each nested scalar in turn and assert exactly one failure whose subject is the enclosing list's path (lists are compared whole). Keep the >= N guard, updated to the new count.
- R8: use SERVICE_TAG.fullmatch.

## Not in this WP
- R7 (environment-level fields are allow-listed, so a future environment setting is dropped — fails open; rule parameters fail closed): one sentence in WP03's guide.
