---
affected_files: []
cycle_number: 1
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-07T14:39:29Z'
reviewer_agent: claude
wp_id: WP03
---

# WP03 review (Opus): REJECT, cycle 1

1. [MAJOR] PyIsoDateTime.java:142-143 computes ISO week 1 from the week that contains 1 January. ISO week 1 is the week that contains 4 January (CPython `_isoweek1monday`). 2022-W01-1 gives 2021-12-27 instead of 2022-01-03. Fix: start from Monday on or before 1 January, and add 7 days when 1 January is Friday, Saturday or Sunday. Add vectors for 2022-W01-1, 2027-W01-1 and 2010-W52-7.
2. [MAJOR] 17 forms where CPython 3.11's C `fromisoformat` (core's interpreter) differs from the port:
   - Python accepts a fraction after the hour or minute (T03.5, T0304.5, +05.5, +05:30.5), a trailing :06 after seconds, and a NUL terminator.
   - Python drops a trailing odd digit (2026W013 130Z -> 13:00Z).
   - Python refuses a Z that is not last (T03:04Z05).
   - Python ignores offset microseconds when the whole-second offset is 0.
   Fix: match the C behaviour, with a vector for each. The differential harness is in scratchpad/review-wp03/diff/.
3. [MINOR] WriteBodies.java:149: the detail text should be "wpId, rows and context, and optionally operation".
4. [MINOR] PyIsoDateTimeTest: assert each branch is actually reached (exception message per branch, or JaCoCo branch coverage of PyIsoDateTime).
