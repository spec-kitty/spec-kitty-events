---
affected_files: []
cycle_number: 2
mission_slug: release-protection-posture-01M74ZWP
reproduction_command:
reviewed_at: '2026-11-11T11:45:02Z'
reviewer_agent: reviewer-renata
wp_id: WP01
---

# WP01 review, cycle 2: REJECT (R1 residual only; R2–R6, R8 verified)

## [MAJOR] R1 residual — network faults under gh's generic "Sigstore verification failed" header still become verdicts
`_is_verification_outcome` (≈line 335) accepts the bare marker "verification failed", which also matches gh's generic "✗ Sigstore verification failed" header; `_INFRA_PATTERN` (lines 46-56) misses Go network errors. These reconstructed stderrs (fragments present in the gh 2.89 binary) are all classified as verdicts today:
1. `Warning: failed to initialize GitHub verifier: failed to get trusted root from TUF client Get "https://tuf-repo.github.com/…": dial tcp: lookup tuf-repo.github.com: no such host\n✗ Sigstore verification failed\nError: …`
2. `✗ Sigstore verification failed\nError: failed to fetch bundle with URL: Get "https://x": EOF`
3. `✗ Sigstore verification failed\nError: context deadline exceeded`
4. `✗ Sigstore verification failed\nError: dial tcp 1.2.3.4:443: connect: network is unreachable`
Scenario: TUF DNS fails during the daily run → every rc asset WARN → exit 0 (FR-010).

Fix:
- Verdict markers (case-insensitive) ONLY: "policy verification failed", "no matching certificateidentity", "no attestations found", "expected" together with san/source/signer/predicate, "artifact does not match digest", "failed to verify signature", "failed to verify log inclusion". Remove the bare "verification failed" and "failed to verify" markers.
- Infra pattern additionally: "no such host", "dial tcp", "eof" (word-bounded), "deadline exceeded", "network is unreachable", "connection refused", "connection reset", "i/o timeout", "failed to initialize", "trusted root", "tuf", "failed to fetch", "unexpected status". Infra still wins over verdict markers.
- Add the four strings above to the exit-2 parametrisation (prerelease and final), keep the two genuine gh 2.89 verdict strings as verdict cases:
  - `✗ Policy verification failed\nError: … no matching CertificateIdentity found, last error: expected SourceRepositoryRef to be "refs/tags/x", got "refs/heads/y"`
  - `✗ No attestations found for subject sha256:…`
