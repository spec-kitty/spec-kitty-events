---
affected_files: []
cycle_number: 1
mission_slug: cloudevents-envelope-registry-upcaster-01M5Z6VM
reproduction_command:
reviewed_at: '2026-10-27T18:24:08Z'
reviewer_agent: claude-reviewer
wp_id: WP04
---

# WP04 review: changes requested (claude-reviewer)

The corpus passes in both bindings (Java runs 358 tests: 355 cases plus 3; Python runs 364). Probes outside the corpus show places where Java and Python disagree.

## [MAJOR]
1. `java/src/main/java/ai/speckitty/events/cloudevents/CanonicalJson.java:127`: `Double.toString` does not match Python `repr` for subnormal numbers. The JDK prints 2 digits even when 1 digit would round-trip. Probe: transport payload `"reason": 5e-324` gives Java `4.9e-324` and Python `5e-324`. Also `1e-323` gives `9.9e-324` and `1e-323`. Fix: try rounding to 1 significant digit and keep that form when it round-trips. Add unit asserts.
2. `Upcaster.java:613` compares the slugs with `JsonNode.equals`, but Python uses `==`, where `1 == 1.0 == True`. Probe: a flat lane row with `"mission_slug": 1, "feature_slug": 1.0` (or `true` and `1`). Python gives `INVALID_MEMBER`, Java gives `MISSION_SLUG_CONFLICT`, so the first code and the strict exception differ. Fix: compare numbers and booleans the way Python does.
3. The ULID regex at `Upcaster.java:31` is case-insensitive for ASCII only, while Python `re.IGNORECASE` also folds Unicode letters.
   - Transport `event_id` `01J0000000000000000000K101` (Kelvin sign): Python maps it, Java gives `INVALID_ID`.
   - Aggregate `mission/01J0000000000000000000MſN1/wp/WP01` (long s): Python maps it and it is valid, Java gives `AGGREGATE_UNMAPPABLE`.
   - The same value in a lifecycle `aggregate_id` produces different goldens.
   Pick one rule. I prefer adding `re.ASCII` to the Python ULID and UUID regexes in `_upcast.py` and `models.py`. Pin the choice with corpus cases.

## Minor (open issues)
- Jackson `StreamReadConstraints`: an integer of 1001 digits maps in Python but is `NOT_JSON` in Java. Nesting deeper than 500 levels also differs.
- Python `upcast_line` accepts NaN and Infinity and maps the row, then `canonical_bytes` raises.
- Read-schema patterns anchored with `$`: `harness.observed` `data.harness` `"claude\n"` is valid in Python but invalid in Java (networknt). Use `(?![\s\S])` like the other patterns.
- The edits to `_upcast.py`, the corpus generator and the fixtures are outside WP04's owned_files.
- `ensure_jdk25.sh` on macOS: `find -maxdepth 3` cannot reach `Contents/Home`, and macOS has no `sha256sum`.

## OK
- Script safety: the checksum is verified before extraction or use.
- The wrapper SHA-256 is pinned.
- Makefile wiring (`test-full` runs `test-java`) and `.gitignore`.
- C-002 (nothing copied) and C-006 (not published).
- networknt does not assert formats, which matches Python (Feb 30, 24:00 and 23:59:60 are valid in both).
- The Jackson 3 deviation is accepted.
