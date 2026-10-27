---
affected_files: []
cycle_number: 1
mission_slug: cloudevents-envelope-registry-upcaster-01M5Z6VM
reproduction_command:
reviewed_at: '2026-10-27T18:38:18Z'
reviewer_agent: claude-reviewer
wp_id: WP05
---

# WP05 review feedback

docs/architecture/cloudevents-envelope.md is stale after the release (factual contradictions):
- L3/L4 (description, doc_status draft) and L12: "Proposed design. Nothing here has shipped." The registry, profiles, upcaster, corpus and Java binding shipped in 11.3.0.
- L13-16: "timing (3a and 3b) is still open" and section-4 choices "are proposals to confirm" contradict ADR 0001 now fully decided (CHANGELOG, COMPATIBILITY).
- L20-21: "The row-level upcaster contract is deliberately not specified here (section 9)" contradicts the new section 9.
Everything else verified (registry 36/26/8, 363 cases, codes, detection order, limits, versions, frontmatter, tests, ruff).
