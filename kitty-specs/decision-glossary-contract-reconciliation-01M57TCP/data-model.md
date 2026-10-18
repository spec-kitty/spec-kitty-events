# Data Model: Decision and glossary contract reconciliation (11.0)

| Entity | Change |
|---|---|
| `Event` | `lamport_clock` is now `Optional[int]` (≥ 0). |
| `ReducedDecisionPointState` | Gains `reopened_from_deferred: bool`, always present. The anomaly kinds gain `unfoldable_terminal_combination`. |
| Glossary payloads | Re-modelled as in research.md §G. Nested types: `GlossaryFinding`, `SenseRef`, `GlossaryActor`, `GlossaryProvenance`, `ScopeRef`. |
| `StepCheckpointedPayload` | New. |
| `ReducedGlossaryState` | `term_candidates[term]`, `term_senses[(scope, term_surface)]`, plus `checkpoints[step_id]`. |
| `ClarificationRecord` | Gains `conflict_id`. `semantic_check_event_id` becomes optional. |
