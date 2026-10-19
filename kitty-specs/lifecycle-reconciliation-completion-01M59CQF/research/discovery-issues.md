# Discovery: events#119 (+ #62)

## HEADLINE: #119 is already partly delivered
- events PR #124 (MERGED 2026-10-18, stijn-dejongh, branch `issue-119-mission-lifecycle-reconciliation`) "feat(lifecycle): schemas + fixtures for MissionReopened, FollowUpRecorded, ReviewerSelfApproval (#119)". Added ReviewerSelfApprovalPayload to project_lifecycle.py, registered 3 types in validator dispatch, new schema, 4 valid + 11 invalid fixtures (min_version 10.6.0). Did NOT use a closing keyword, so #119 is still open, unassigned, 0 comments.
- Explicitly deferred by #124: mirroring SignificanceEvaluated / DecisionTimeoutExpired (needs an operator decision on a RACI actor-shape change that could break RuntimeActorIdentity). Populating LOCAL_ONLY_EVENT_TYPES was left to #118.
- Not evidenced as done: a documented reconciliation of all four modules (lifecycle, project_lifecycle, mission_audit, mission_next); WPStatusChanged/MissionCreated/ProjectInitialized/WPCreated/RuntimeActorIdentity coverage checked against core's ledger; fold-through-reducer evidence; CHANGELOG/COMPATIBILITY.
- Since then 11.0.0 shipped (#128): optional Event.lamport_clock, LOCAL_ONLY_EVENT_TYPES removed, 4-tuple status_event_sort_key. The pin quoted in #119 (>=10.5.1,<11) is stale; core moved to >=11.0.0,<12 (#6425).

## spec-kitty-events
| # | State | Disposition | Note |
|---|---|---|---|
| 117 | open | Parent epic | Children: 118 (closed), 119, 120 (closed), 121, 122 |
| 124 (PR) | merged | Partial delivery of #119 | Cite it as prior work and do not redo it |
| 118 | closed | Refs | Scope A, append_event (10.8.0, PR #127); its pattern is what B integrates after |
| 120 | closed | Refs, pattern | Scope C, 11.0.0 (PR #128); same kind of reconciliation mission, its opening comment is a template; envelope decision D1 (core writes full Event) carries over |
| 62 | open, ready | Fold-in | WPStatusChanged summary lane breadth (add done and rejection/rework cases); test-only, same lifecycle surface. Closes |
| 121 | open | Out of scope | Scope D, BDD suite |
| 122 | open | Out of scope | Scope E, docs |
| 114 | open | Refs / out of scope | Two Lane enums; touches WPStatusChanged lane vocabulary but is a separate cleanup; do not fold |
| 92 | open | Refs | CloudEvents type registry; only where a payload rides `type` |
| 72 | open, assigned stijn-dejongh | Out of scope | status-diary causal ordering |
| 57 / 59 | open | Out of scope | MINORs on live-work / CI canary |

## spec-kitty (core)
| # | State | Disposition | Note |
|---|---|---|---|
| 6438 | open | Refs (core follow-up) | Build MissionReopened/FollowUpRecorded/ReviewerSelfApproval payloads from the 11.0 models; consumes #124 |
| 6185 | open, assigned MOES-Media | Refs | Reroute lifecycle events to produce spec-kitty-events (producer in Python for GA) |
| 6411 (+6412/6413/6414) | open | Refs | Adopt 11.0.0 umbrella; decisions/glossary/clock follow-ups from #120 |
| 6399 / 6425 | closed | Refs | append_event adopted; pin now >=11.0.0,<12 |
| 6117 | open | Refs | Mission Write API epic |

No ticket supersedes #119. No one else has claimed it; the only actor is stijn-dejongh via #124.
