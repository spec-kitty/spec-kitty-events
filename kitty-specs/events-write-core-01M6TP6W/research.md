# Research: write-core port inventory

> Produced by a research delegate (OpenCode, kimi-k3) on 2026-11-07 and accepted by the mission orchestrator. Repo A is this repository; Repo B is spec-kitty/spec-kitty at the pinned commit `512567ee`.

**Scope.** Repo A is the current `spec-kitty-events` worktree. Every Repo B path and line below is at the pinned commit `512567eee71e31bb39f580c4f7acf8b865e9d1b2`, also recorded in Repo A's `service/core-pin.json:1`. Repo A paths and lines are from its current working tree. The two active issue scopes are the canonical writer (#160) and the authority port (#161); #155 is the Java-side cut-over. The existing research note already establishes that there is no production appender, byte-matching encoder, or lane state machine in Repo A (`docs/architecture/services/command-cli-research.md:108-122`).

## 1. Lane state machine (core)

### 1.1 Vocabulary and entry points

Core has **11 enum values**, not merely the nine display lanes: `genesis`, `uninitialized`, `planned`, `claimed`, `in_progress`, `for_review`, `in_review`, `approved`, `done`, `blocked`, `canceled`, in that order (`src/specify_cli/status/models.py:29-68`). `genesis` and `uninitialized` are non-display; `uninitialized` is a read-only “no events” sentinel and is never a valid persisted transition endpoint (`src/specify_cli/status/models.py:39-55,71-79`). The ordinary authorable/display set has nine values, and only `done` and `canceled` are terminal (`src/specify_cli/status_lanes.py:22-35`; `src/specify_cli/status/wp_state.py:77-84,507-570`).

The sole production authority for both edges and force/guard decisions is `WPState`; `ALLOWED_TRANSITIONS` is only a derived projection for tests and visualisation, not a second gate (`src/specify_cli/status/transitions.py:1-13,36-53`; `src/specify_cli/status/wp_state.py:7-12`). `validate_transition` normalizes both lane strings with `strip().lower()`, applies aliases, returns `Unknown lane: <original>` for either unknown side, catches `InvalidTransitionError`, and returns `(False, exc.reason)` rather than throwing it (`src/specify_cli/status/transitions.py:56-60,68-100`). `WPState.transition_to` is the operation that actually throws `InvalidTransitionError(source,target,reason)` (`src/specify_cli/status/wp_state.py:56-66,198-209`). The normal emit pipeline converts a false decision to `specify_cli.status.emit.TransitionError(error_msg)` (`src/specify_cli/status/transition_pipeline.py:370-388`); orchestrator-api maps that exception to `TRANSITION_REJECTED` (`src/specify_cli/orchestrator_api/wp_lifecycle.py:847-873`).

The only lane alias is `doing -> in_progress`; `in_review` is a first-class lane, not an alias (`src/specify_cli/status_lanes.py:30-35`; `src/specify_cli/status/wp_state.py:698-711`). The derived edge set contains **29 edges** (`tests/status/test_transitions.py:67-71`).

### 1.2 Complete structural transition table

“Guard” below summarizes the non-forced decision. All other targets not listed are structurally illegal, but most can be forced as described in §1.4.

| From | To | Non-forced guard | Source |
|---|---|---|---|
| genesis | planned | none | `wp_state.py:236-259` |
| genesis | canceled | none | `wp_state.py:250-251` |
| planned | claimed | nonblank actor, then dependency-ready must not be `false` | `wp_state.py:299-320` |
| planned | blocked | none | `wp_state.py:307-308` |
| planned | canceled | none | `wp_state.py:307-308` |
| claimed | in_progress | nonblank workspace context, then dependency-ready must not be `false` | `wp_state.py:329-347` |
| claimed | blocked | none | `wp_state.py:337-338` |
| claimed | canceled | none | `wp_state.py:337-338` |
| in_progress | for_review | subtasks exactly `true`, then implementation evidence exactly `true` | `wp_state.py:356-393` |
| in_progress | approved | approval evidence with nonblank reviewer/reference and verdict `approved` | `wp_state.py:364-395,578-593` |
| in_progress | planned | nonblank reason | `wp_state.py:396-399` |
| in_progress | blocked | none | `wp_state.py:364-373` |
| in_progress | canceled | none | `wp_state.py:364-373` |
| for_review | in_review | nonblank actor; review-conflict helper is deliberately allow-only | `wp_state.py:409-425,610-627` |
| for_review | blocked | none | `wp_state.py:417-418` |
| for_review | canceled | none | `wp_state.py:417-418` |
| in_review | approved | complete `review_result`, then approved approval evidence, then cross-field consistency | `wp_state.py:434-463,596-607,630-677` |
| in_review | done | same as approved | `wp_state.py:442-463` |
| in_review | in_progress | complete `review_result`, then cross-field consistency | `wp_state.py:442-463,630-677` |
| in_review | planned | complete `review_result`, then cross-field consistency | `wp_state.py:442-463,630-677` |
| in_review | blocked | complete `review_result`, then cross-field consistency | `wp_state.py:442-463,630-677` |
| in_review | canceled | complete `review_result`, then cross-field consistency | `wp_state.py:442-463,630-677` |
| approved | done | approval evidence | `wp_state.py:472-493,578-593` |
| approved | in_progress | nonblank `review_ref` | `wp_state.py:480-497` |
| approved | planned | nonblank `review_ref` | `wp_state.py:480-497` |
| approved | blocked | none | `wp_state.py:480-498` |
| approved | canceled | none | `wp_state.py:480-498` |
| done | *(none)* | terminal; force required to leave | `wp_state.py:507-526` |
| blocked | in_progress | none | `wp_state.py:529-548` |
| blocked | canceled | none | `wp_state.py:541-542` |
| canceled | *(none)* | terminal; force required to leave | `wp_state.py:551-570` |
| uninitialized | *(none)* | no structural transitions | `wp_state.py:262-296` |

Backward/rework edges are therefore deliberately evidence-gated, not inherently force-only: `in_progress -> planned` needs `reason`; `approved -> {in_progress,planned}` needs `review_ref`; `in_review -> {in_progress,planned,blocked,canceled}` needs `review_result`; `for_review` has no backward edge (`src/specify_cli/status/wp_state.py:375-400,442-498`; `tests/status/test_wp_state_force_free_contract.py:89-148`).

### 1.3 Exact guards, inputs, errors, and precedence

The guard input protocol is `actor`, `workspace_context`, `subtasks_complete`, `implementation_evidence_present`, `reason`, `review_ref`, `evidence`, `force`, `review_result`, `current_actor`, and `dependency_ready` (`src/specify_cli/status/wp_state.py:34-53`; `src/specify_cli/status/models.py:1099-1128`). `current_actor` is **not read by the FSM**; claim collisions live in `work_package_lifecycle`, not `wp_state`. A structured actor is projected to its `tool` (fallback `role`) before guard evaluation (`src/specify_cli/status/models.py:244-257`; `src/specify_cli/status/transition_pipeline.py:373-384`).

| Guard / order | Condition and fields read | Exact failure text | Raise / mapping |
|---|---|---|---|
| Planned claim: actor first | `actor` absent/blank | `Transition requires actor identity` | `PlannedState.guard_for`, `wp_state.py:310-312` |
| Planned claim: dependency second | `dependency_ready is False` | `Transition planned -> claimed blocked: unsatisfied dependencies (force with reason to override)` | `wp_state.py:31,313-320` |
| Claimed start: workspace first | `workspace_context` absent/blank | `Transition claimed -> in_progress requires workspace context` | `wp_state.py:340-342` |
| Claimed start: dependency second | `dependency_ready is False` | `Transition claimed -> in_progress blocked: unsatisfied dependencies (force with reason to override)` | `wp_state.py:31,343-347` |
| Review handoff: subtasks first | `subtasks_complete is not True` (therefore `None` fails) | `Transition in_progress -> for_review requires completed subtasks or force with reason` | `wp_state.py:382-387` |
| Review handoff: implementation evidence second | `implementation_evidence_present is not True` (therefore `None` fails) | `Transition in_progress -> for_review requires implementation evidence or force with reason` | `wp_state.py:388-392` |
| Review approval evidence | `evidence` null, or its `review.reviewer` blank, `review.reference` blank, or `review.verdict != "approved"` | `Transition to approved/done requires evidence (reviewer identity and approval reference)` | `_check_reviewer_approval`, `wp_state.py:29-30,578-593`; called at `394-395,492-493` |
| Review handoff reason | `reason` absent/blank | `Transition in_progress -> planned requires reason` | `wp_state.py:396-399` |
| Initial review claim | actor absent/blank | `Transition requires actor identity` | `wp_state.py:420-424`; `_check_no_review_conflict` then always allows at `610-627` |
| Approved rewind | `review_ref` absent/blank | `Transition requires review_ref (review feedback reference)` | `wp_state.py:494-497` |
| Every `in_review` exit | `review_result` null | `Transition from in_review requires review_result (structured review outcome)` | `wp_state.py:454-463,630-637` |
| `in_review` exit result fields | missing/blank `review_result.reviewer` | `Transition from in_review requires review_result with reviewer` | `wp_state.py:638-642` |
| same | missing/blank `review_result.verdict` | `Transition from in_review requires review_result with verdict` | `wp_state.py:643-644` |
| same | missing/blank `review_result.reference` | `Transition from in_review requires review_result with reference` | `wp_state.py:645-647` |
| `in_review -> approved/done` with separate evidence | separate `evidence` non-null must independently satisfy approval guard | same reviewer-approval text above | `_check_in_review_approval`, `wp_state.py:596-607`; called at `459-462` |
| `in_review -> approved/done` without separate evidence | `review_result.reviewer` nonblank, verdict `approved`, reference nonblank | same reviewer-approval text above | `wp_state.py:596-607` |
| Cross-field consistency | non-null `review_ref` differs from `review_result.reference` | `review_ref must match review_result.reference` | `wp_state.py:650-660` |
| Cross-field consistency | `evidence.review` triple `(reviewer,verdict,reference)` differs from `review_result` triple | `Review evidence must match review_result` | `wp_state.py:661-677` |
| Done-evidence construction (before FSM) | request evidence is non-null but `review` is not a dict, or any of `review.reviewer`, `review.verdict`, `review.reference` is falsy/blank | `Moving to done requires evidence with review.reviewer review.verdict, and review.reference` | `emit.TransitionError`, `_build_done_evidence`, `src/specify_cli/status/emit.py:461-489`; invoked before FSM at `transition_pipeline.py:361-365` |
| Unnoted verdict-lane force exit | `force=true` out of `in_review` or `approved`, no `review_result`, non-migration actor, blank reason | `<wp> cannot leave <lane> by force without a note. --force requires a non-blank --note explaining why the review lane is being left; as an operator pass \`--force --note <why>\` (\`--reason <why>\` on \`agent status emit\`).` | `emit.TransitionError`, `transition_pipeline.py:222-242,359`; constant at `models.py:234-241` |

`in_review` guard order matters: completeness, approval for `approved`/`done`, then consistency (`wp_state.py:454-463`). Review-evidence consistency therefore is not interchangeable with the generic “review evidence invalid” check in a port.

The pipeline derives the two review-handoff booleans outside the FSM: for unforced `in_progress -> for_review`, subtask completeness comes from the authored subtask roster plus event-sourced subtask state, while implementation evidence is true if any prior transition for that WP exists (`src/specify_cli/status/emit.py:492-548`; `transition_pipeline.py:133-177`). A missing implementation-evidence hint is inferred even under force, whereas subtask inference is skipped under force (`transition_pipeline.py:147-176`). Default workspace context is `<execution_mode>:<repo_root or feature_dir>` in the single flat shell; the older same-WP batch shell deliberately sets `default_workspace_context=False`, so a missing context fails (`transition_pipeline.py:289-304,331-335`; `src/specify_cli/status/emit.py:1082-1135`).

### 1.4 Force, illegal edges, terminals, aliases

`WPState.check_transition` first refuses **every** target of `genesis`, even under force. For a non-edge or for a guarded edge, `force=true` bypasses the structural edge and all FSM guards, but still requires nonblank `actor` **and** `reason`; otherwise it returns `Force transitions require actor and reason` (`src/specify_cli/status/wp_state.py:164-196`). `can_transition_to` intentionally never uses force (`wp_state.py:151-162`). Force also grounds out in `transition_to`, which throws `InvalidTransitionError` on refusal (`wp_state.py:198-209`).

The implementation is broader than the comment “terminal force-exit … to any display lane”: the code only excludes `genesis`, so a correctly populated forced request can select `uninitialized`, an existing lane, or the same lane, from **any** source state, including `uninitialized` itself. This follows the actual `target == GENESIS` / non-edge branches at `wp_state.py:164-187`, and is a parity case worth pinning rather than “correcting” to the prose.

Additional lifecycle-only force rules do not live in `check_transition`: `start_implementation_status` allows `operator_force_note` only when `review_lane_exit` is explicitly enabled and the current lane is `for_review`, `in_review`, or `approved`; otherwise it throws `WorkPackageStartRejected(FORCE_NOTE_REQUIRED)` or `--force only applies to a WP in for_review, in_review or approved; <wp> is in '<lane>'` (`src/specify_cli/status/work_package_lifecycle.py:210-225`). Its force exit from `in_review` without the operator note is a review `WorkPackageClaimConflict`, while from `approved` it is `WorkPackageStartRejected` with a bespoke instruction (`work_package_lifecycle.py:228-263`). The common pipeline’s separate mandatory-note guard is in §1.3.

### 1.5 Claim rules (`WP_ALREADY_CLAIMED`)

These are **not** guards in `wp_state.py`. `WorkPackageClaimConflict` extends `TransitionError`; its normal message is `WP <id> is already claimed for implementation by '<holder>'` or `... for review ...`; a submission withdrawal has a separate message (`src/specify_cli/status/work_package_lifecycle.py:60-85`). Orchestrator-api maps that class to `WP_ALREADY_CLAIMED` with `claimed_by` and `requesting_actor` metadata (`src/specify_cli/orchestrator_api/wp_lifecycle.py:587-611,681-706`).

Implementation-start rules, all evaluated in-lock on one reduced current state:

* `genesis` rejects as not finalized (`work_package_lifecycle.py:323-336`).
* `planned` emits `planned -> claimed` then `claimed -> in_progress` as one batch (`work_package_lifecycle.py:338-372`).
* `claimed` requires the existing and requesting actors to be compatible, else raises `WorkPackageClaimConflict`; compatible resumes continue to `in_progress` (`work_package_lifecycle.py:374-400`).
* `in_progress` is idempotent for the same actor; a different actor conflicts, except when the requester is the implementer of record resuming after a reviewer’s rework, in which case it is a no-op (`work_package_lifecycle.py:402-415`; implementer lookup `_admits_implementer_of_record`, `183-207`).
* Other lanes reject with `WP <id> is in '<lane>', cannot start implementation`, unless the explicit review-lane-exit path applies (`work_package_lifecycle.py:417-453`).

Actor compatibility projects both structured and compact actors to the bare tool, returns compatible if either side is missing or the tools match, and lets the existing generic placeholders `implement-command`, `unknown`, `user` yield to a real claimant (`work_package_lifecycle.py:32-57,117-158`). This is role-blind by design; review distinctness is a separate role channel (`work_package_lifecycle.py:117-135`). The current actor and role must come from **one reduction including annotations**: lane and actor from the transition fold, role from the annotation-folded `role` slot, never by splitting a compact actor (`src/specify_cli/coordination/status_service.py:260-301`).

Review-start rules:

* `for_review -> in_review` only needs an actor; the FSM’s conflict helper deliberately never blocks, including stale reviewer roles (`wp_state.py:420-425,610-627`; `work_package_lifecycle.py:490-514`).
* Already `in_review` is a no-op unless the current holder has role exactly `reviewer` (after `strip()`), a nonblank actor, and a **different** requester. A blank holder or a non-review-role holder allows; same actor allows; requester role is ignored. Collision names the holder (`src/specify_cli/status/review_claim_predicate.py:52-87`; `work_package_lifecycle.py:516-531`).
* Other lanes reject with `WP <id> is in '<lane>', cannot start review` (`work_package_lifecycle.py:533`).

A same-actor idempotent start can therefore produce **zero new events**, not an illegal self-transition or a replay of an already-sent event (`work_package_lifecycle.py:402-415,516-531`). The protocol port must decide how this maps to its acknowledgement/corpus model.

### 1.6 Dependency gating and required on-disk data

Only `planned -> claimed` and `claimed -> in_progress` consult `dependency_ready`, and only an explicit `False` refuses. `None` means no verdict and passes; `True` passes; force with actor+reason bypasses at `check_transition` (`src/specify_cli/status/models.py:1119-1128`; `wp_state.py:313-320,343-347`). Other edges ignore it (`src/specify_cli/status/dependency_verdict.py:67-82`). Core’s emit shells always compute a verdict in-lock from the same reduction used for `from_lane`; no declared dependencies is a satisfied verdict (`dependency_verdict.py:38-61`; `src/specify_cli/status/emit.py:420-458,995-1004`).

A dependency is satisfied exactly when:

* its current lane is `approved` or `done`; or
* it is `canceled` and its reduced WP state has `reason_source == "operator"`.

Everything else—including `for_review`, `in_review`, `blocked`, an invalid lane, or a missing dependency (defaulted to `planned`)—is unsatisfied (`src/specify_cli/core/dependency_graph.py:35-100`; `src/specify_cli/status_lanes.py:37-77,80-96`). Cancellation provenance is projected by the reducer; an explicit `reason_source` wins, otherwise synthetic prefixes `Force move to ` and `move-task: ` mean synthetic, blank means synthetic, and any other nonblank reason means operator (`src/specify_cli/status/reducer.py:68-77,94-117`).

Declarations come from the **WP Markdown frontmatter** on the primary planning surface, under `tasks/`, not from `tasks.md`, `lanes.json`, or the event log. `parse_wp_dependencies` reads typed frontmatter (`src/specify_cli/core/dependency_graph.py:103-126,129-177`), but the authoritative emit path intentionally reads only raw frontmatter’s `dependencies` key, avoiding a second full-log reduction and unrelated full-model validation (`src/specify_cli/status/emit.py:323-347,384-402`). File matching is `^<wpId>(?:[-_.]|\.md$)` over `tasks/*.md`, excluding `readme.md`; zero files means no dependencies, multiple files is unresolvable (`emit.py:591-607,384-402`). Accepted legacy forms are `[]`, comma-separated `WP01, WP02`, and bare `WP01`; non-string non-list/malformed values become an unsatisfied verdict (`src/specify_cli/status/wp_metadata.py:187-205`; `src/specify_cli/status/emit.py:405-417`). Unreadable/ambiguous/malformed declarations become an unsatisfied verdict, affecting only the two guarded edges and remaining force-bypassable (`src/specify_cli/status/dependency_verdict.py:64-82`; `src/specify_cli/status/emit.py:420-458`).

**Can a write service with only the checkout Mission directory obtain all inputs?** For `checkout_append`, yes if “Mission directory” includes its ordinary `tasks/` and event log: all declarations, current/reduced lanes, cancellation provenance, actors/roles, review/subtask data are there. For `coordination_ref_commit`, not by assumption: core deliberately resolves declarations from the **primary planning surface**, not the coordination write surface (`src/specify_cli/status/emit.py:323-383`), and ADR-0007’s ref writer need not touch any checkout (`docs/adr/0007-write-service-commits-coordination-status-by-ref.md:114-140`). A future ref-commit implementation therefore needs an explicit trusted primary-checkout dependency input or a reviewed change to that rule; merely reading the destination tree’s Mission directory is not core parity. The current mission serves only `checkout_append`, so this is a forward-design constraint rather than a blocker.

### 1.7 Sanctioned annotations

Annotations do not traverse the FSM. `wp_state.annotate` requires a WP id matching `^WP\d+$` and a nonempty `WPInnerStateDelta`; otherwise it raises `ValueError` (`src/specify_cli/status/wp_state.py:714-769`; pattern source `models.py:99-103`). An annotation row has `event_id`, `kind: annotation`, `wp_id`, `at`, `actor`, and `delta` (`src/specify_cli/status/models.py:873-905`). Delta slots include `subtasks`, `note`, `review`, `agent`, `role`, other resolved-binding fields, `release_runtime_claim`, and claim/release metadata; scalar empty strings normalize to absent, and an all-empty delta is refused (`models.py:694-870`). These rows matter to subsequent claim/role state and batch sequencing; treating annotations as payload-free IDs is insufficient.

## 2. Refusal vocabulary mapping

Repo A and pinned core share the same 15 URN suffixes and the same generic mapping: `dependencies_unsatisfied -> DEPENDENCIES_NOT_SATISFIED`, `claim_held_by_other_actor -> WP_ALREADY_CLAIMED`, all other reasons -> `TRANSITION_REJECTED` (`contracts/mission-write/schemas/WriteRefusalReason.yaml:8-38`; core `src/specify_cli/status/write_service_protocol.py:186-224`). All three codes are HTTP **409** (`contracts/mission-write/schemas/WriteRefusal.yaml:12-47`). Repo A’s current `WriteRefusalCode.yaml` retains exactly those bindings (`contracts/mission-write/schemas/WriteRefusalCode.yaml:11-16`).

| URN suffix | Code / HTTP | Core producer(s) |
|---|---|---|
| `unknown_lane` | `TRANSITION_REJECTED` / 409 | Unknown from/to after alias resolution, `transitions.py:81-91` |
| `illegal_transition` | `TRANSITION_REJECTED` / 409 | No structural edge without force, and every target `genesis`, `wp_state.py:164-187`; terminal lanes have empty targets at `519-520,563-564` |
| `force_requires_actor_and_reason` | `TRANSITION_REJECTED` / 409 | `_check_force`, `wp_state.py:189-196` |
| `actor_required` | `TRANSITION_REJECTED` / 409 | `planned -> claimed`, `wp_state.py:310-312`; `for_review -> in_review`, `420-424` |
| `dependencies_unsatisfied` | `DEPENDENCIES_NOT_SATISFIED` / 409 | `wp_state.py:31,313-320,343-347`; verdict semantics in `core/dependency_graph.py:35-100`; orchestrator preflight also emits this code at `orchestrator_api/wp_lifecycle.py:510-549` |
| `workspace_context_required` | `TRANSITION_REJECTED` / 409 | `claimed -> in_progress`, `wp_state.py:340-342` |
| `implementation_evidence_required` | `TRANSITION_REJECTED` / 409 | Both unmet subtasks and missing implementation evidence, `wp_state.py:382-392` |
| `reason_required` | `TRANSITION_REJECTED` / 409 | `in_progress -> planned`, `wp_state.py:396-399` |
| `review_ref_required` | `TRANSITION_REJECTED` / 409 | `approved -> in_progress/planned`, `wp_state.py:494-497` |
| `reviewer_approval_required` | `TRANSITION_REJECTED` / 409 | `_check_reviewer_approval` and `_check_in_review_approval`, `wp_state.py:578-607`; call sites `394-395,459-462,492-493` |
| `review_evidence_invalid` | `TRANSITION_REJECTED` / 409 | Missing/incomplete `review_result`, mismatched `review_ref`, or contradictory evidence, `wp_state.py:630-677`; `in_review` order at `454-463` |
| `force_note_required` | `TRANSITION_REJECTED` / 409 | Pipeline `_refuse_unnoted_review_exit`, `transition_pipeline.py:222-242`; lifecycle-only force misuse also maps through `WorkPackageStartRejected`, `work_package_lifecycle.py:213-225`; shared constant `models.py:234-237` |
| `done_evidence_incomplete` | `TRANSITION_REJECTED` / 409 | `_build_done_evidence`, `emit.py:461-489`, before FSM at `transition_pipeline.py:361-365` |
| `claim_held_by_other_actor` | `WP_ALREADY_CLAIMED` / 409 | `WorkPackageClaimConflict`, `work_package_lifecycle.py:60-81`; implementation collisions `375-376,403-413`, review re-claim `516-530`, submission withdrawal `250-262` |
| `start_rejected` | `TRANSITION_REJECTED` / 409 | `WorkPackageStartRejected`, `work_package_lifecycle.py:84-85`; examples not finalized at `333-334`, implementation wrong-lane at `453`, review wrong-lane at `533`, force misuse at `213-225` |

**Every URN has a producer.** No reason is dead vocabulary. Note two documentation traps in the contract itself: `dependencies_unsatisfied`’s prose says only “neither approved nor done,” omitting the operator-provenanced-canceled exception that core implements (`contracts/mission-write/schemas/WriteRefusalReason.yaml:17-18`; `src/specify_cli/status_lanes.py:52-77`), and `unknown_lane` says “nine” although core’s `Lane` enum also includes `genesis` and `uninitialized` (`WriteRefusalReason.yaml:9-10`; `models.py:29-68`).

**Core guards outside this reason vocabulary, which must not be silently assigned a made-up URN:**

* The lane-specific for-review **commit** gate emits `TRANSITION_REJECTED` without any of the 15 URNs (`src/specify_cli/orchestrator_api/wp_lifecycle.py:730-756`). This is separate from the subtask/implementation-evidence FSM guard.
* Missing `wp_id`, `to_lane`, or `actor` raises `TypeError` before validation (`transition_pipeline.py:324-325`); this maps naturally to protocol `REQUEST_MALFORMED`, not `start_rejected`.
* Legacy alias collapse to the current lane is an intentional mirror-only no-op, not an illegal self-edge (`transition_pipeline.py:349-357`; `src/specify_cli/status/emit.py:664-676`).
* `WorkPackageStartRejected` has a mapped reason, but other lifecycle/storage refusals such as `StatusContractError`, `StoreError`, or the lock timeout belong to their own codes, not `TRANSITION_REJECTED` (`coordination/status_service.py:66-67`; `status/store.py:58-63`; `status/locking.py:68-98`).

Pinned core’s client enum has 29 codes and retired coordination-worktree values; Repo A now has 27, removing five worktree codes and adding `COORDINATION_REF_MOVED`, `COORDINATION_REF_CHECKED_OUT`, `COORDINATION_LEDGER_UNREADABLE` (core `write_service_protocol.py:97-183`; Repo A `WriteRefusalCode.yaml:10-64`; ADR-0007 D-7 at `docs/adr/0007-write-service-commits-coordination-status-by-ref.md:244-267`). The 15 guard mappings are unchanged.

## 3. Wire shapes

### 3.1 Core client construction

`RequestContext` wire fields are exactly `actor`, `checkout`, `missionDirectory`, `writeSurface`, `destinationRef` (`src/specify_cli/status/write_service_protocol.py:230-247`). `TransitionUnit` has `wpId`, nonempty `rows`, `context`; `TransitionBatch` has 1–64 units; `AnnotationRequest` has `wpId`, one `row`, `context` (`write_service_protocol.py:250-289,56-60`). The `TransitionUnit` constructor only enforces nonempty rows; batch enforces the 64 limit (`write_service_protocol.py:258-274`). Repo A’s schemas match and enforce max 64 (`contracts/mission-write/schemas/TransitionUnit.yaml:10-34`; `TransitionBatch.yaml:5-19`; `AnnotationRequest.yaml:5-26`).

`transition_unit_from(transition, checkout, repository_root)`:

1. serializes each port row with `to_dict()`;
2. requires the first row’s `actor` and `wp_id` to be nonempty **strings**;
3. requires every row to have the same `wp_id`;
4. maps absent write contract to `checkout_append`, or `primary_checkout_append` / `coordination_transaction_append`;
5. sets context actor from the first row;
6. makes `checkout` relative to `repository_root`, and `missionDirectory` relative to `checkout`, with resolved containment checks and `/` separators;
7. leaves `destination_ref=None` (`src/specify_cli/status/write_service_protocol.py:292-330`).

Rows are ordered transition first, annotations afterwards, matching `WorkPackageTransition.rows` (`src/runtime/next/event_bridge.py:60-79`; `emit.py:846-860`). A lane row serialized by `StatusEvent.to_dict` always includes `event_id`, `mission_slug`, `wp_id`, `from_lane`, `to_lane`, `at`, `actor`, `force`, `execution_mode`, `reason`, `review_ref`, `evidence`, `policy_metadata`; it adds `review_result`, `reason_source`, and `mission_id` only when non-null (`src/specify_cli/status/models.py:522-594`). Actor can be a string or a validated structured dict (`models.py:166-231,542-568`), but `transition_unit_from` rejects a dict in the first row because it requires `isinstance(actor,str)` (`write_service_protocol.py:310-316`). That is a live client/row compatibility discrepancy to resolve, not something the Java writer should “fix” by dropping structure.

An annotation row has `event_id`, `kind: annotation`, `wp_id`, `at`, `actor`, `delta` (`models.py:873-905`). There is no `annotation_request_from` helper and no transport; standalone annotations still bypass the EventBridge at the pin (`src/runtime/next/event_bridge.py:26-30`; `docs/architecture/services/event-ledger-writers-and-readers.md:54-59`). The protocol file explicitly says no transport exists (`write_service_protocol.py:9-16`).

`DurabilityAck` reads `eventIds`, nonnegative `appendedLines`, mandatory `fsynced:true`, nullable/string `statusCommit`, boolean `replayed` (`write_service_protocol.py:365-391`). `BatchDurabilityAck` requires at least one unit ack and `fsyncs = 0` only when every unit is replayed, otherwise exactly 1 (`write_service_protocol.py:394-411`). Core’s enum/build helpers are shape checks only, not authority validation (`write_service_protocol.py:9-13`).

### 3.2 Repo A comparison and discrepancies

Repo A preserves the three request shapes and 64-unit cap, and additionally requires every row to have a ULID `event_id`, to name the request WP, and—when `mission_id` is present and non-null—to match the URL mission (`contracts/mission-write/schemas/EventRow.yaml:1-19`; `service/command-cli/.../WriteBodies.java:125-141`). `WriteBodies` also rejects unknown/missing fields, malformed relative paths, and inconsistent batch `missionDirectory`/`writeSurface`/`destinationRef`, naming a failing unit as `#/units/<i>` (`WriteBodies.java:56-84,144-177`). The shared-location requirement is also normative for one commit (`contracts/mission-write/schemas/TransitionBatch.yaml:1-4`; ADR-0007 D-1 at `docs/adr/0007-write-service-commits-coordination-status-by-ref.md:137-140`).

Material differences from the pinned client:

* **Coordination surface:** pinned core knows `coordination_transaction_append`; Repo A accepts `checkout_append` and `coordination_ref_commit` only (`write_service_protocol.py:75-94`; `contracts/mission-write/schemas/WriteSurface.yaml:1-19`; `WriteBodies.java:27-39`). Pinned `transition_unit_from` can never produce the new value or a non-null destination ref (`write_service_protocol.py:317-330`).
* **Root checkout:** pinned core permits any checkout containing the Mission (root, lane worktree, coordination worktree), whereas Repo A says `checkout_append` is the repository root checkout only, and a lane worktree is never a status surface (`write_service_protocol.py:300-308`; `contracts/mission-write/schemas/RequestContext.yaml:37-48`; `WriteSurface.yaml:2-12`).
* **Destination required for ref commit:** Repo A requires `destinationRef` to be present in every context and nonempty for `coordination_ref_commit` (`RequestContext.yaml:11-28,67-77`; `WriteBodies.java:162-168`).
* **Batch semantics:** core’s older emit batch is same Mission **and same WP** (`src/specify_cli/status/emit.py:1052-1079`), while the protocol schema/Repo A permits different WPs as long as the shared location fields match (`contracts/mission-write/examples/TransitionBatch.two-units.yaml:1-27`; `WriteBodies.java:75-83`). Do not accidentally port the older same-WP restriction to the service contract.
* **Client reason/code drift:** pinned core’s endpoint/ack shapes are useful, but its refusal code and surface enums are stale relative to Repo A 1.0.1-SNAPSHOT (§2). Use Repo A’s schemas as the service-side authority.

Repo A’s `DurabilityAck` requires at least one `eventId`, `appendedLines >= 0`, `fsynced:true`, `statusCommit` null or 40–64 lowercase hex, and `replayed`; `BatchDurabilityAck` has 1–64 unit acks and `fsyncs` 0 or 1 (`contracts/mission-write/schemas/DurabilityAck.yaml:11-76`; `BatchDurabilityAck.yaml:11-30`). Those match the pinned readers except for the new ref-commit meaning of `statusCommit`.

## 4. Byte-exact encoder

### 4.1 Actual core algorithm

The one status-row function is exactly:

```python
return json.dumps(sanitize_event_for_log(event_dict), sort_keys=True)
```

(`src/specify_cli/status/store.py:293-300`). It emits no newline itself; callers add one LF (`store.py:333-360`). With no keyword overrides, Python uses `ensure_ascii=True` and `allow_nan=True`, and separators `", "` and `": "`. Repo A’s `persistence.serialize_record` spells those same defaults except it explicitly sets `allow_nan=False`, then ASCII-encodes and appends LF (`src/spec_kitty_events/persistence.py:185-196`). Thus `persistence` is a useful byte oracle for finite values, but it is **not** a complete status encoder oracle for NaN/infinities, which core’s default `json.dumps` writes as bare `NaN`, `Infinity`, and `-Infinity` unless the caller rejects them.

Sanitization order is deep-copy, recursive PII stripping, then top-level timestamp replacement (`src/specify_cli/events/sanitizer.py:126-166`):

* At every dict nesting level, including dicts inside lists/tuples, remove keys `machine_name`, `hostname`, `workspace_path`, `developer_name`, `developer_email`; preserve other fields and do not mutate input (`sanitizer.py:24-68`). Circular/revisited container identities become `{}` on that branch, with the seen set copied per sibling path (`sanitizer.py:43-68`).
* Pop top-level `session_started_at` and `session_ended_at` unconditionally. If both parse via `datetime.fromisoformat`, replace them with `session_duration_s = int((ended - started).total_seconds())`. If either is missing or malformed, emit no computed replacement (`sanitizer.py:71-118`; `src/kernel/clock.py:233-237`).
* `int()` truncates toward zero, not floor and not nearest-even; the output is a JSON integer and negative durations are possible (`sanitizer.py:115-116`). If both valid timestamps exist, this assignment overwrites an existing `session_duration_s`; if no timestamps exist, an existing duration survives (`sanitizer.py:89-118,146-148`; test intent `tests/specify_cli/events/test_sanitizer.py:184-219,257-274`).
* Timestamp replacement is **top-level only**; nested keys of the same names are untouched except for PII recursion (`sanitizer.py:71-88,140-144`). Parse failures are swallowed per field with only a debug log. A mixed naive/aware pair can parse successfully and then fail subtraction; that `TypeError` is outside the two parse `try` blocks and is not swallowed (`sanitizer.py:94-116`). This edge needs an explicit parity case or rejection policy, not accidental Java behaviour.

### 4.2 `json.dumps(..., sort_keys=True)` requirements for Java

The Java writer must reproduce all of these, recursively:

* **Formatting:** `, ` between members/items and `: ` between key/value; `{}`/`[]` as normal; one LF per row, never CRLF (`store.py:293-300,360`; `persistence.py:185-196`; fixture README `src/spec_kitty_events/conformance/fixtures/ledger_append/README.md:26-33`).
* **Ordering:** keys sorted lexicographically by Unicode code point at every object depth. A supplementary character sorts after BMP by code point, not by raw UTF-16 surrogate units. Repo A already has a code-point comparator in `CanonicalJson.java:24-37`.
* **Strings:** quote and backslash escaped; short escapes for `\b`, `\f`, `\n`, `\r`, `\t`; all other U+0000–U+001F as lowercase `\u00xx`; `"` and `\` as `\"` and `\\`; `/` unescaped. Every non-ASCII code point is `\uXXXX` lowercase; non-BMP becomes a UTF-16 surrogate pair, e.g. 😀 -> `\ud83d\ude00` (`persistence.py:188-196`; actual non-ASCII fixture `conformance/fixtures/ledger_append/valid/non_ascii_text.json:13,28`).
* **Numbers:** Python arbitrary-size integers remain integers; a float `1.0` retains `.0`, `-0.0` remains `-0.0`, and other finite doubles use shortest round-trip repr. Positional form applies for decimal exponent `-4 <= exp < 16` (so `0.0001`, `1000000000000000.0`), scientific outside it (`1e-05`, `1e+16`), exponent sign and at least two digits. Core’s default also permits non-finite tokens, unlike `persistence` and `CanonicalJson` (`store.py:293-300`; `persistence.py:188-196`; `CanonicalJson.java:117-168`).
* **Other values:** `None -> null`, `True/False -> true/false`; arrays preserve order; no normalization of number types after parsing other than what the input model did (`PythonJson.java:14-24,58-69`; `models.py:570-594,897-905`).

### 4.3 Repo A’s existing Java coverage

`service/event-log/.../PythonJson.java` is a **decoder only**. It produces insertion-ordered maps, lists, strings, `BigInteger`, `Double`, booleans and null, including non-finite values (`PythonJson.java:14-24,41-69,103-111`). It has no serializer or sanitizer.

`java/.../CanonicalJson.java` is an encoder, but for a different canonical form: compact separators (no spaces), raw non-ASCII UTF-8, code-point key order, integers via `BigInteger`, booleans/null as required (`CanonicalJson.java:14-21,24-90,93-115`). It cannot be used unchanged for status append. Its `pythonFloat` implementation handles signed zero, positional/scientific thresholds, exponent formatting, and shortest round-trip digits, with tests including subnormals and extrema (`CanonicalJson.java:117-168`; `UpcastCorpusTest.java:97-112`). It is a useful implementation seed, but it rejects NaN/infinities, so its acceptance contract must be decided against core rather than assumed (`CanonicalJson.java:138-143,112`).

### 4.4 Core persistence is not the new appender’s durability model

Pinned `status.store.append_raw_rows_atomic` reads the whole existing log, adds a newline to an unterminated old tail, writes old+new content to a temporary file, fsyncs it, renames over the log, and fsyncs the parent directory (`src/specify_cli/status/store.py:333-404`). Verified variants then read the log back (`store.py:424-517`). This is full-file atomic replacement, **not** the protocol’s one `O_APPEND` write per unit/batch. It also silently normalizes a torn tail instead of refusing it (`store.py:356-360`), contrary to #160’s ruling.

The relevant locked `O_APPEND` pattern is core’s interim `kernel.event_log_append`: open `O_RDWR|O_APPEND`, check the final byte on the same descriptor, one `os.write` for the whole unit, rollback to the pre-write size on write error, then `os.fsync` (`src/kernel/event_log_append.py:208-218,247-258,271-322`). That module explicitly excludes `status.events.jsonl` because status keeps the Mission lock and `status.store` primitives (`kernel/event_log_append.py:40-44`). Repo A’s `persistence.append_event` also checks torn tail and performs one `O_APPEND` write, but never fsyncs (`src/spec_kitty_events/persistence.py:199-225`). Therefore the new implementation must compose the byte rule, lock, and durability requirements itself; no existing whole implementation is sufficient.

## 5. Locks and torn tail

### 5.1 Core lock contract at the pin

For status, the key is the **Mission directory name**, never a bare slug: `<git-common-dir>/spec-kitty-locks/<mission-dir-name>.status.lock` (`src/specify_cli/status/locking.py:16-20,179-190`; `src/kernel/locks.py:114-121`). Core resolves the common dir through `kernel.git_topology.git_common_dir`, with non-git fallback locations that are irrelevant to the root-git-only write service (`status/locking.py:138-176`). The default `feature_status_lock` timeout is `UNBOUNDED_LOCK_WAIT=-1`; a shared finite bound of 10 seconds exists for outage-shaped takes, and finite waits raise `FeatureStatusLockTimeoutError` with class-level `error_code="STATUS_LOCK_HELD"` (`status/locking.py:45-58,68-98,309-329`). For the service, choose an explicit bounded wait—do not inherit the unbounded default.

The primitive opens `O_RDWR|O_CREAT` with no-follow and mode `0600`, tries exclusive `flock(LOCK_EX|LOCK_NB)` on POSIX, and retries every 0.1 s (`src/kernel/locks.py:226-245,349-372,410-470`). Windows uses `msvcrt.locking(...LK_NBLCK,1)` and the corresponding unlock (`kernel/locks.py:241-260`). Release truncates rather than unlinks, preserving the lock inode; the file also carries lock-record metadata (`kernel/locks.py:21-46,263-278,392-406`). Status wraps it with per-thread reentrancy and a separate `.holder` sidecar (`status/locking.py:101-135,201-306`). These holder/reentrancy details are not required merely for cross-process exclusion, but they mean Repo A is not a semantic clone of the entire lock API.

### 5.2 Repo A lock implementation and remaining work

`LedgerLocks.forThisOs()` chooses FFM flock on non-Windows and channel lock on Windows (`service/event-log/.../LedgerLocks.java:5-16`). `FlockLedgerLock` supports Linux x64/AArch64 and macOS constants, opens absolute lock paths `O_RDWR|O_CREAT|O_NOFOLLOW|O_CLOEXEC` mode `0600`, calls `flock(LOCK_EX|LOCK_NB)`, retries `EINTR`, and closes exactly once (`FlockLedgerLock.java:17-43,67-120,122-155`). `ChannelLedgerLock` tracks same-JVM holders, opens no-follow owner-only channels, uses `FileChannel.tryLock`, and releases safely (`ChannelLedgerLock.java:18-107`). `LedgerLock.acquire` polls every 100 ms and throws `LedgerLockTimeoutException` at the supplied deadline (`LedgerLock.java:12-53`). No reentrancy or `.holder` metadata exists, so the service must not recursively take the same lock in its own write pipeline.

The interop test runs only when `SPEC_KITTY_LOCK_INTEROP_PYTHON` is set, tests both locks on POSIX and channel on Windows, and proves mutual exclusion plus intact appends (`LockInteropSpikeTest.java:43-70,81-129`). On Linux it explicitly expects `FileChannel.lock` **not** to exclude `fcntl.flock`, while FFM flock is GO (`LockInteropSpikeTest.java:120-128`; summary `docs/architecture/services/event-log-lock-and-append.md:59-78`). The CI job checks out Repo B at `service/core-pin.json` under `core/`, runs `uv sync --frozen`, syncs packs, and sets both Python and peer environment variables before `:event-log:test --tests '*LockInteropSpikeTest'` (`.github/workflows/service.yml:470-553`).

`TornTailRepair` quarantines a fragment and truncates under a different leaf `<ledger>.lock`, with 10 s timeout (`service/event-log/.../TornTailRepair.java:13-41,55-90`). **Do not call it for status writes.** The spike helper instead refuses when the last byte is not LF, then writes the complete line in one append-channel write and forces it (`.../spike/lockinterop/SpikeRows.java:26-31,52-75`). This is the required policy, but only test code exists (`docs/architecture/services/event-log-lock-and-append.md:80-97`).

Missing production pieces are the Mission lock-path derivation, a status appender, and one-descriptor read/check/write logic; the spike itself documents these and native registration as remaining (`docs/architecture/services/event-log-lock-and-append.md:73-78`). No production class currently maps timeout to `STATUS_LOCK_HELD`; the only timeout exception is generic (`LedgerLockTimeoutException.java:7-14`).

### 5.3 Native-image downcalls

`FlockLedgerLock.Native` creates three downcall handles: `open`, `flock`, `close` (`FlockLedgerLock.java:48-65`). `CommandCliHints` currently registers only `META-INF/build-info.properties` (`service/command-cli/.../CommandCliHints.java:6-14`), and the command-cli native configuration only disables toolchain detection and names the image (`service/command-cli/build.gradle.kts:144-159`). The boot jar manifest has title/version but no `Enable-Native-Access` (`build.gradle.kts:67-72`); only the event-log JVM test task sets `--enable-native-access=ALL-UNNAMED` (`service/event-log/build.gradle.kts:35-43`). `NativeSmokeTest` currently checks readiness/performance and the fail-closed write, not an FFM lock (`NativeSmokeTest.java:18-24,28-69`).

For GraalVM 25, each of those three descriptors must be registered (`open`: int return, pointer/int/int parameters, `firstVariadicArg:2`, `captureCallState:true`; `flock`: int return, int/int parameters, capture state; `close`: int return, int parameter, no capture). This can be a `reachability-metadata.json` `foreign.downcalls` entry under `META-INF/native-image`, or a custom `RuntimeForeignAccess` feature. The official GraalVM JDK 25 FFM reference specifies this metadata and says FFM support is enabled by default, with native access granted via `--enable-native-access`; it also warns against freezing downcall handles/native addresses into the image heap: <https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api>. The nested `Native` holder’s lazy initialization in Repo A is compatible with the latter concern (`FlockLedgerLock.java:44-65`).

Finally, command-cli currently has **no dependency** on `:event-log` or `:domain-model`; its dependencies are Spring web and test libraries only (`service/command-cli/build.gradle.kts:36-43`). These module dependencies, native metadata, a native locked-write smoke, and JVM/native-access test configuration must all be added deliberately.

## 6. Write location

Pinned `transition_unit_from` performs resolution client-side: it receives absolute `checkout` and `repository_root`, resolves the port’s `write_location` relative to the checkout, and rejects paths outside the expected bases (`src/specify_cli/status/write_service_protocol.py:292-330`). The resolved location comes from the opaque `EventLogWriteContract`; direct append targets `primary_checkout_append`, coordination targets `coordination_transaction_append` (`write_service_protocol.py:317-329`; `src/runtime/next/event_bridge.py:21-24,60-75`). Only relative paths travel (`contracts/mission-write/schemas/RequestContext.yaml:2-8`). Repo A already checks lexical no-absolute/no-`..`/no-backslash/no-drive paths (`WriteBodies.java:27-30,144-157`).

For `checkout_append`, the service can and must independently verify, from its configured `--git-common-dir` (`service/command-cli/.../ServeOptions.java:8-16,72-89`):

1. `writeSurface == "checkout_append"`; refuse a `coordination_ref_commit` request until step 4 (`contracts/mission-write/schemas/WriteSurface.yaml:2-16`; ADR-0007 D-4 at `docs/adr/0007-write-service-commits-coordination-status-by-ref.md:204-218`).
2. `checkout == "."`, the root checkout only; Repo A explicitly forbids a lane-worktree status surface (`RequestContext.yaml:37-48`). Resolve that root checkout independently and verify that its real git common dir is the configured real path. The current service only computes a repository **digest** from the configured GCD; it does not retain/resolve a checkout root (`RepositoryIdentity.java:11-36`; `ServeRuntime.java:17-25`). A git/worktree resolver is therefore new work.
3. The normalized real `<root>/<missionDirectory>` stays under the real root and identifies the URL `missionId` (normally through the Mission’s own metadata). Lexical containment alone is not enough for links/TOCTOU; no host path is supplied by the client (`RequestContext.yaml:2-8`).
4. The event log is exactly `<missionDirectory>/status.events.jsonl` (`src/specify_cli/status/store.py:50,111-113`). Take `<gcd>/spec-kitty-locks/<missionDirectory basename>.status.lock`, not a lock named by `mission_slug` (`status/locking.py:179-190`).
5. Mission/WP existence should be resolved before domain processing under the contract’s 404 codes (`WriteRefusalCode.yaml:17-20`), rather than letting a path request mint arbitrary directories. Current appender creation semantics in core allow parent creation, but write-location verification must precede it (`store.py:333-356`).

`RepositoryIdentity.of` hashes the real GCD for the handshake only (`RepositoryIdentity.java:20-36`); it does not verify a Mission path or surface. `ServeRuntime` carries `ServeOptions`, so the configured GCD is available to wiring (`ServeRuntime.java:17-25,46-53`).

`coordination_ref_commit` is a different operation served only from migration step 4. It requires a nonempty short `destinationRef`; reads the old log blob at `<missionDirectory>/status.events.jsonl` in that branch tip, appends bytes without filters, builds a commit from git objects, and compare-and-swaps `refs/heads/<destinationRef>` (`contracts/mission-write/schemas/RequestContext.yaml:17-28`; ADR-0007 D-1 at `docs/adr/0007-write-service-commits-coordination-status-by-ref.md:114-145`). A batch must share location/ref and becomes one commit (`ADR-0007:137-140`). Its ack’s `statusCommit` is the new commit (`ADR-0007:161-162`), and replay uses event-id commit trailers (`ADR-0007:164-174`). ADR-0007 D-4 says resolution moves into the service at that step and a mismatched destination is `WRITE_LOCATION_UNVERIFIED` (`ADR-0007:204-218`). Do not accidentally implement it as a hidden special case of checkout append.

## 7. Idempotency

There is **no core persisted-id replay protocol** at the pin. `EVENT_ID_CONFLICT` is explicitly protocol-only (`src/specify_cli/status/write_service_protocol.py:124-132,170-183`). Core only has:

* duplicate-id rejection within one transaction or against earlier calls in that transaction, throwing `BookkeepingDoubleEventId` before append (`src/specify_cli/coordination/transaction.py:977-1005,1031-1047`; class/code at `coordination/transaction_errors.py:112-116`);
* reducer dedupe by `event_id` (first occurrence wins, with reconstruction-seed exceptions), which is read-side behavior, not writer replay/conflict detection (`service/event-log/.../StatusReducer.java:22-27,90-115,132-147`; core projection `status/reducer.py:104-117`);
* post-append readback checking a transition’s id, slug, optional mission id, WP, and target lane, not full row equality (`src/specify_cli/status/store.py:303-330,460-509`).

Repo A’s normative protocol is stronger: an identical full-unit retry returns `replayed:true` and `appendedLines:0`, with `fsynced:true` because the original append was synced (`contracts/mission-write/schemas/DurabilityAck.yaml:31-49,67-76`). Partial presence or same id/different content is `409 EVENT_ID_CONFLICT`; any repeated event id inside one unit or batch is `409 BOOKKEEPING_DOUBLE_EVENT_ID` (`contracts/mission-write/schemas/WriteRefusalCode.yaml:23-24,57-58`). A fully replayed batch has `fsyncs:0`; otherwise it has one fsync (`BatchDurabilityAck.yaml:22-31`; core reader `write_service_protocol.py:394-411`).

The comparison is against **sanitized rows as JSON values**, not client-supplied whitespace or raw request bytes (`contracts/mission-write/schemas/TransitionUnit.yaml:6-9`). Each unit is judged independently: an all-present/identical unit is replayed; only some of its ids present, or any contradictory row, conflicts. New units in the same batch still land in one write/fsync; a fully replayed batch writes nothing and syncs zero times. A duplicate already present in a legacy log multiple times needs an explicit implementation decision if the service is to avoid ambiguous replay; the contract itself only defines request/log id matching, not a uniqueness repair protocol (`EventRow.yaml:6-15`).

Idempotency checking must be inside the same Mission lock as reduction, domain validation, and append; otherwise two services/processes can both decide “new” and append duplicates. A refusal must write nothing (`docs/architecture/services/event-log-lock-and-append.md:80-92`).

## 8. Repo A seams

### 8.1 command-cli

* `WriteGate.persist()` ignores the request and always returns `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` with `The write core is not built yet; nothing was written.` (`service/command-cli/.../WriteGate.java:5-21`). It is the main replacement seam.
* `WriteBodies` parses bytes and returns only `Optional<Malformed>`; its unit/batch/annotation methods discard valid parsed content (`WriteBodies.java:47-67,87-123`). It enforces exact fields, WP/ULID/mission ids, shared batch location and 64 units (`WriteBodies.java:27-39,56-84,104-177`). The new flow must return a typed validated request (or retain the parsed tree) rather than reparse/lose it.
* `WriteController` applies draining then shape and calls `gate.persist()` without mission or body (`WriteController.java:25-62`). All three endpoints are routed there (`WriteController.java:37-50`). It needs separate unit/batch/annotation domain/application calls and success serialization while preserving edge order.
* `RefusalCode` has only seven edge/fail-closed values (`domain/service/RefusalCode.java:3-30`). It needs the contract’s domain codes and fixed statuses/titles.
* `RefusalBody` has `code,detail,instance,status,title`, serializes a small ordered map plus LF, and supports no `type` member (`adapter/in/http/edge/RefusalBody.java:12-53`). Guard refusals need a reason URN in `type`; ordinary refusals can continue to omit it. Its no-reflection map pattern is native-friendly and should be retained (`RefusalBody.java:17-19,35-52`).
* `ServiceWiring.writeGate()` constructs the empty gate (`config/ServiceWiring.java:56-64`), while `OperationsWiring` creates only body checker/activity/drain beans (`adapter/in/http/operations/OperationsWiring.java:12-39`). These are the composition seams for repository/location resolver, lock, authority, appender, and write service. The application already knows `ServeRuntime` and therefore GCD (`ServiceWiring.java:26-43`).
* Package roots differ: command-cli classes are `org.speckitty.commandcli.*`; shared event/domain classes are `org.speckitty.missionstatus.*` (`ArchitectureRulesTest.java:20-32`; `Lane.java:1-4`).

### 8.2 domain-model and event-log

`Lane` already covers all 11 core values and has no `doing` alias (`service/domain-model/.../Lane.java:12-33,56-63`). Alias behavior must live in the write authority, not by adding a persisted enum alias.

`WpState` currently folds only `lane`, `lastTransitionAt`, `lastEventId`, `forceCount`, cancellation reason/source, and implementer-of-record (`WpState.java:5-14`). `StatusEvent` carries event id/time/WP/from/to/force/reviewRef/reason/reasonSource/claimAgent/actor/seedKey, but not the actual evidence or review-result payload (`StatusEvent.java:15-38`). `RowClassifier` validates evidence/review-result shape and then discards their contents when constructing that reduced `StatusEvent` (`RowClassifier.java:85-113`). The authority needs current actor, role, review/evidence/review_result and subtask data added to the write-side fold, not just `Lane`.

`StatusReducer` is pure and implements dedupe/order/rollback precedence plus cancellation and implementer projections (`StatusReducer.java:22-34,90-115,185-305`). It currently deduplicates by id, folds transitions, then treats annotations only as WP ids (`StatusReducer.java:74-115`). It does **not** fold annotation delta role, subtasks, agent/release, or review state. `LineKind.Annotation` itself contains only `wpId` (`domain/model/LineKind.java:10-18`). These are the minimum model/decoder/reducer changes needed for claims and sequential batches.

There is also a known read-side divergence to avoid inheriting blindly: the Java reducer says it remains at 10.4.6 and has not ported the 10.5.1 clock-ahead approved-departure lift (`StatusReducer.java:37-51`; `docs/architecture/services/event-ledger-writers-and-readers.md:130-140`). For authoritative current-state computation, this can change current lane in adversarial histories; it needs a core-parity corpus case rather than an assumption that the existing read fold is complete.

### 8.3 Architecture and Gradle rules

`DomainModelBoundaryTest` permits only the model package and selected JDK packages, excluding frameworks, JSON, file APIs, and `java.util.concurrent`; it has planted non-vacuity tests (`DomainModelBoundaryTest.java:20-45,54-77`). A framework-free write domain belongs under `org.speckitty.missionstatus.domain.*` or command-cli domain/application and must respect this rule.

`EventLogBoundaryTest` currently allows exactly three packages: codec, reducer, and ledger. Codec cannot do file IO/data binding; reducer is pure; ledger file mechanics may use only JDK and cannot use domain-model/codec (`EventLogBoundaryTest.java:29-46,57-90,104-116`). `ONLY_THE_THREE_PACKAGES` will fail a new appender package until a new explicit rule is added (`EventLogBoundaryTest.java:88-90,166-169`). This aligns with #160’s “own ArchUnit rule,” and with the package guide saying the write append adapter goes into event-log under its own rule (`service/README.md:193-205`).

Command-cli’s onion test prohibits domain files/frameworks, application web/files, cross-adapter dependencies, and configuration use outside config (`ArchitectureRulesTest.java:40-89`). Store orchestration should therefore stay behind application ports, with file/git implementations in outbound adapters and composition in config.

Gradle modules are `:domain-model`, `:event-log`, `:mission-status-api`, `:command-cli` (`settings.gradle.kts:21-39`). Event-log has `api(project(":domain-model"))` and Jackson core; domain-model has no production dependencies (`service/event-log/build.gradle.kts:22-32`; `service/domain-model/build.gradle.kts:21-26`). Command-cli needs explicit project dependencies (§5.3).

### 8.4 Python reference and fixture consumers

`src/spec_kitty_events/persistence.py` normalizes/validates canonical event records, serializes sorted/default-spaced/ASCII JSON, refuses non-finite floats, rejects torn tail, and performs one append write without fsync (`persistence.py:165-225`). It is not a status-lane appender and knows nothing about `StatusEvent` lane guards (`persistence.py:1-33`).

`ledger_append` is registered as a special fixture type excluded from generic event-envelope validation (`conformance/loader.py:17-56,104-115`). Its loader returns each document plus manifest `id` and `expected_result` (`loader.py:141-159`). The manifest entries carry `path`, `expected_result`, `event_type`, `notes`, `min_version`, and `fixture_type:"ledger_append"` (`conformance/fixtures/manifest.json:3614-3622` and the rest at `3623-3810`). Documents have `input_kind` (`event|dict|model`), `input`, optional `model`, `existing_ledger`, `nan_field`, and either `expected_line` or `expected_error` (`conformance/fixtures/ledger_append/README.md:8-24`). The packaged runner creates the prior bytes, calls `append_event`, and compares appended bytes or refusal/no-side-effect (`conformance/test_ledger_append.py:46-67`). Package data explicitly ships its README and valid/invalid JSON (`pyproject.toml:109-111`). There are 20 valid and 7 invalid cases, matching the research note (`docs/architecture/services/command-cli-research.md:120-122`).

**There is no Java runner for `ledger_append` in the current tree.** The Java-binding corpus runner is `UpcastCorpusTest`, which reads `producer_contract/fixtures/upcast/cases`, not `conformance/fixtures/ledger_append` (`java/src/test/.../UpcastCorpusTest.java:22-35,46-84`; configured path in `java/build.gradle.kts:18-20,39-51`). Its only encoder-specific assertions are Python float cases (`UpcastCorpusTest.java:97-112`). The packaged fixture comment merely says a second binding “is held” to the documents; no such Java implementation exists yet (`conformance/test_ledger_append.py:1-7`). A `status_append` family therefore needs both a new special loader/runner and a new Java fixture consumer, plus package-data entries modelled on `pyproject.toml:109-111`; do not claim an existing Java `ledger_append` test to reuse.

## 9. Existing corpora and core-oracle generation

There is no committed transition `(state,unit) -> ack/refusal` corpus in pinned core:

* `tests/contract/fixtures/mission_write_refusal_parity.yaml` is a static inventory of codes and each reason’s source text, not outcomes for transition inputs (`tests/contract/fixtures/mission_write_refusal_parity.yaml:1-8,39-54`). Its test checks enum/schema/source-text agreement (`tests/contract/test_mission_write_contract.py:174-217`).
* `tests/specify_cli/status/test_wp_state.py` programmatically checks all 29 edges and disallowed pairs, plus guard equivalence, in Python (`test_wp_state.py:1-8,71-147`). `tests/status/test_transitions.py` calls one in-code “golden-baseline parity row” type, not a portable corpus (`test_transitions.py:1-5,30-33`). Dependency, claim, and pipeline behaviors live in test methods (`tests/status/test_dependency_guard.py:145-211,285-397,530-617`; `tests/status/test_review_claim_role_aware.py:145-181,287-334`).
* `tests/review/fixtures/parity` is for the separate pre-review test gate, with verdict aggregation outcomes, not the WP lane FSM (`tests/review/test_transition_gate_parity.py:1-21,47-83`).
* Repo A’s nightly corpus compares **read-service** Mission/project responses over core’s `kitty-specs`, byte for byte (`.github/workflows/service.yml:580-648`; core `scripts/ci/mission_status_corpus_compare.py:1-20`). It does not test transitions, guards, or append.

A test-only generator can run core directly at the pin by checking out `spec-kitty/spec-kitty` under `core/`, installing its locked environment with `uv sync --frozen`, and syncing packs—the exact pattern used by lock-interop (`.github/workflows/service.yml:519-537`). Generate inputs from a small language-neutral case schema; call `validate_transition`/`GuardContext` for pure cases, `dependency_readiness_for_wp` with reduced synthetic snapshots for dependency cases, and lifecycle/pure claim predicates for claim cases. Emit `(ack | code,reason)` and generate a compact intermediate state suitable for the Java fold. Use the pinned enum mapping to derive reason URNs, but derive the actual outcome by executing core, not by duplicating its guard logic in the generator.

For claim/lifecycle cases that require files, create isolated temporary Mission directories and seed event rows/frontmatter; for pure cases, no git or full core CLI is required. The packaged Python conformance runner must not import core at runtime—ADR-0002/test-only dependency rules prohibit that. Instead, commit generated expected results and use CI only to regenerate and diff them. The pin is validated as 40 lowercase hex by `detect` (`.github/workflows/service.yml:86-106`), and test-only peers are excluded from release runs for lock-interop (`.github/workflows/service.yml:477-484`); use the same restriction for a generator/drift job.

Useful existing test inputs to translate into fixture cases include every edge/guard matrix, tri-state dependency readiness, canceled provenance, malformed/ambiguous frontmatter, force terminal/illegal targets, alias normalization, all 15 reasons, role-aware review claims, generic implementation actors, idempotent zero-event starts, and batch sequencing (`test_wp_state.py:71-147`; `test_dependency_guard.py:285-397,530-617`; `review_claim_predicate.py:56-87`). They are source material, not an already usable oracle.

## 10. Risks and open questions, ranked

1. **Authority inputs are currently missing, not merely unvalidated.** Repo A drops review/evidence payloads and every annotation delta, while core claim rules require actor **and annotation-folded role**, plus review evidence and subtasks (`RowClassifier.java:85-113`; `LineKind.java:10-18`; `coordination/status_service.py:260-301`). Extend the model/decoder/write fold before porting guards. Otherwise `WP_ALREADY_CLAIMED`, rework, role-aware review, and later batch units cannot be correct.
2. **The protocol has no operation discriminator for generic transition vs start-implementation/start-review/no-op.** Core claim logic lives outside `wp_state` and can return zero events (`work_package_lifecycle.py:288-302,374-453,456-533`). Decide how from/to and batch shape select those semantics, how zero-event acks are represented, and how a row’s declared `from_lane` is checked against reduced state. Put these in the parity corpus before writing the controller.
3. **Authoritative current state can diverge from the existing read reducer.** Java has not ported the approved-departure ordering lift and annotations do not fold state (`StatusReducer.java:37-51,74-115`). Add core-generated adversarial histories; do not treat `StatusReducer` as already authority-complete.
4. **Byte identity has many traps:** default spaces, ASCII/surrogate escaping, code-point order, integer-vs-float, `-0.0`, exponent thresholds, non-finite defaults, and sanitizer ordering (`store.py:293-300`; `persistence.py:185-196`; `CanonicalJson.java:14-21`). `CanonicalJson` cannot be reused unchanged. Generate core fixtures for all of them, including nested PII and mixed/missing timestamps.
5. **Location and dependency resolution require git/root semantics missing from the service.** Request paths contain no host root (`RequestContext.yaml:2-8`), while command-cli retains only GCD and a digest (`ServeOptions.java:72-89`; `RepositoryIdentity.java:20-36`). A symlink-safe root/Mission resolver must be built, and future coordination dependencies still come from primary, not destination, data (`emit.py:323-383`).
6. **Replay/validation/append must be one lock-critical transaction.** Core has no replay oracle, and current Repo A only read-dedupes (`StatusReducer.java:132-147`). Define all replay/conflict branches, legacy duplicates, and partial batches before append. Holding no lock across those steps creates an immediate duplicate/lost-update race (contract atomicity at `event-log-lock-and-append.md:80-92`).
7. **Durability/failure semantics need low-level tests.** One `FileChannel.write` can be short; fsync and parent-directory entry durability differ from rename; rollback can itself fail. Core’s interim writer cuts to pre-size on write error (`kernel/event_log_append.py:308-330`), while Repo A’s spike only detects short write (`SpikeRows.java:56-74`). Add crash/ENOSPC/short-write tests and never acknowledge after uncertainty.
8. **Native-image FFM registration and module wiring can fail only in release-native execution.** Three downcalls need foreign metadata; command-cli has no hints or event-log dependencies (`FlockLedgerLock.java:48-65`; `CommandCliHints.java:6-14`; `command-cli/build.gradle.kts:36-43`). Require an actual locked write in native smoke on Linux and macOS, not just launcher readiness (`NativeSmokeTest.java:18-24`).
9. **Pinned client and current contract disagree.** Surface enum, destination ref, coordination codes, and root-only checkout changed (`write_service_protocol.py:75-94,317-330`; `WriteSurface.yaml:1-19`). Port domain behavior from the pin, but use Repo A’s 1.0.1 schemas for transport and explicitly handle core’s future client update rather than accepting the retired surface.
10. **Reason precedence is easy to get subtly wrong.** Examples: actor before dependencies, workspace before dependencies, subtasks before evidence, approval before consistency, done evidence before FSM (`wp_state.py:310-320,340-347,382-400,454-463`; `transition_pipeline.py:359-388`). Preserve exact messages internally even though clients branch on code, and do not force URNs onto commit-gate/other `TRANSITION_REJECTED` producers (`orchestrator_api/wp_lifecycle.py:730-756`).
11. **No Java append fixture runner exists; adding a core import to packaged Python tests would violate the dependency boundary.** `UpcastCorpusTest` is unrelated (`UpcastCorpusTest.java:22-35`), and only Python has `test_ledger_append.py` (`conformance/test_ledger_append.py:1-7`). Create independent fixture consumers and CI-only generation/diff, not a runtime core dependency (CI pattern `.github/workflows/service.yml:519-537`).
12. **Some boundary policies are underspecified and need explicit fixtures/rulings:** force to `uninitialized` is code-legal (`wp_state.py:164-187`); structured first-row actor is rejected by pinned client although `StatusEvent` permits it (`write_service_protocol.py:310-316`; `models.py:166-231`); naive/aware mixed session timestamps can throw outside the sanitizer’s catch (`sanitizer.py:94-116`); legacy duplicate ids make replay ambiguous. Pin these rather than making accidental “reasonable” choices.
13. **Architecture/package constraints will fail if implementation is added opportunistically.** `ONLY_THE_THREE_PACKAGES` rejects any fourth event-log package, command-cli domain/application forbid file APIs, and the ledger rule forbids domain-model dependencies (`EventLogBoundaryTest.java:82-90`; `ArchitectureRulesTest.java:55-89`). Design writer-owned package/rules first, with pure domain logic and JDK-only file mechanics, then wire outbound adapters in config.
14. **Performance may conflict with whole-log per-write reduction.** Core does one in-lock reduction per single/batch (`emit.py:995-1004,1165-1180`), which is simplest for parity but O(log size) per write. Choose and benchmark caching/incremental sequencing only after correctness; an unvalidated cache or reorder optimization would be a second authority.

## 11. Planning decisions (2026-11-07)

Each entry gives the decision, why it was chosen, and the alternatives considered.

1. **Order of checks (FR-026), with replay before authority.**
   - Why: a retry of an acknowledged unit carries a stale `from_lane`. Judging the authority first would refuse a legitimate replay (post-spec squad M2).
   - Alternative rejected: authority first, which makes replays impossible.
2. **An `operation` discriminator, an optional field in `1.0.1-SNAPSHOT`.**
   - Why: core's claim conflicts and `start_rejected` come only from `start_implementation_status` and `start_review_status`. The emit pipeline accepts the very same rows (§1.5, squad M1).
   - Alternatives rejected: inferring the operation from the batch shape (ambiguous); applying the claim rules to every unit (changes the codes of `move-task`).
   - Default taken; flagged to the operator. **Ruled 2026-11-08 (maintainer): keep the optional `operation` field in `1.0.1-SNAPSHOT`** (Decision Moment `01M6X2JWES41Y6PGF4EZ8M174K`).
3. **Torn tail and an unreducible log map to `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`.**
   - Why: it is the only existing 500 code that fits. `COORDINATION_LEDGER_UNREADABLE` is bound to the branch tip (ADR-0007), and `BOOKKEEPING_COMMIT_FAILED` to the status commit. Its description is widened in the CHANGELOG.
4. **Lock budget of 10 s, one in-process turn per repository, and `SERVICE_BUSY`.**
   - Why: 10 s is core's bounded status-lock timeout. The contract says writes to one repository are serialized.
   - Alternative rejected: an unbounded wait, which is core's default but blocks the launcher's client.
5. **A stale `from_lane` answers `TRANSITION_REJECTED` with no `type`.**
   - Why: `illegal_transition` is defined as an edge that is not in the machine, which this is not. `type` is optional in `WriteRefusal`. These cases are tagged `oracle: protocol`.
   - **Superseded 2026-11-08 (maintainer ruling, decision 17):** the refusal carries `urn:spec-kitty:write-refusal:stale_from_lane`.
6. **`coordination_ref_commit` answers `WRITE_LOCATION_UNVERIFIED` before the Mission lookup.**
   - Why: ADR-0007 D-4. A coordination Mission need not exist in the root checkout, so a 404 first would be wrong.
7. **A location mismatch on `checkout_append` answers `WRITE_LOCATION_UNVERIFIED`, not `OWNED_MISSION_PATH_REFUSED`.**
   - Why: the contract makes `checkout_append` root-only, so an owned checkout is never a valid status surface. The service's own resolution differs, which is that code's definition.
8. **A separate write fold that ports core's `read_events` and reducer at the pin.**
   - Why: the read reducer discards the authority's inputs and lags core (§8.2, risk 3), and C-010 keeps the read side unchanged.
   - Deviation from #161's wording "build on `StatusReducer`": the port builds on `Lane` and may share the ordering comparator only where parity cases prove it.
9. **Non-finite numbers and mixed naive/aware session times are `REQUEST_MALFORMED`.**
   - Why: core would write `Infinity`, or raise a `TypeError` it does not catch. Both are deliberate parity exceptions, each pinned by an invalid `status_append` fixture.
10. **The service writes LF on every OS; reading tolerates a trailing CR.**
    - Why: core on Windows writes CRLF through a text-mode file. The divergence is deliberate and recorded.
11. **Replay equality is byte equality of the encoded, sanitized row.**
    - Why: it avoids Python's `1 == 1.0` trap. The first occurrence of a duplicated id counts, as the reducer's first-wins rule does.
12. **The checks core runs above its emit pipeline stay in the client as advisory pre-checks:** `move-task`'s agent-mismatch guard and the lane-specific for-review commit gate.
    - Why: they are command-layer checks, not emit-pipeline guards, and they have no URN. Core #6195's client keeps them.
13. **The parity oracle runs core's real entry points against materialized temporary Missions,** and maps each exception through core's own `REASON_CODE` and its refusal-parity source texts.
    - Why: guard inputs and claim rules are derived in core's pipeline, so a pure-function oracle misses them. Reimplementing core's derivation in the generator would be a second authority that agrees with itself.
14. **SnakeYAML 2.6 (already sha-pinned) for frontmatter.**
    - Why: core's `dependencies` and `subtasks` come in several YAML forms. `SafeConstructor` only, on a bounded block.
    - Supply chain: no new artifact (DIRECTIVE_051 controls: the version is pinned and verified, no install scripts, no new transitive dependency).
## 12. Post-spec squad dispositions

Lenses: scope fidelity (Opus) and contract and parity (Opus), 2026-11-07. Every finding was **folded** into spec `f8e5cd9c`; none was dropped.

| Finding | Disposition |
|---|---|
| Undefined order of checks; replay vs stale `from_lane` (both lenses, MAJOR) | Folded: FR-026, FR-008, FR-017 |
| No operation discriminator (both lenses, MAJOR) | Folded: FR-024 (`operation`), flagged to the operator |
| Write failure and partial lines; NFR-006 unmeetable as worded (MAJOR) | Folded: FR-028 (rollback), NFR-006 reworded |
| No row-kind validation lets a transition bypass the authority (MAJOR) | Folded: FR-027 |
| C-003 vs the new protocol-only codes | Folded: C-003 narrowed, with the outcomes listed |
| Torn-tail code, lock budget, coordination-surface code | Folded: decisions 3, 4, 6 |
| FR-009 gaps (`destinationRef`, 404 vs 422, `OWNED_MISSION_PATH_REFUSED`) | Folded: FR-009, FR-018, FR-026, decision 7 |
| Commit gate has no URN | Folded: C-004, decision 12 |
| `StatusReducer` shared with the read service | Folded: C-010, decision 8 |
| FR-010 too vague | Folded: the exact predicate, allowlist and planted violation |
| NFR-001 flaky | Folded: benchmark, 1 s gate |
| NFR-006 passable without fsync | Folded: fault-injecting channel |
| Legacy duplicates, force to `uninitialized`, mixed timestamps, corpus location, public Python API, `SERVICE_BUSY`, FR-025 testability | Folded: decisions 9 and 11, FR-011, FR-015, FR-001, FR-020, FR-025 |
| `fromisoformat` depends on the Python version | Folded: FR-003 pins the oracle's Python version |
| Message echo conflicts with the no-echo rule | Folded: FR-025 |
| Windows CRLF | Folded: decision 10 |
| ADR-0007 "accepted" wording | Folded: ruling 2 reworded; the ADR status is untouched (C-002) |
15. **The lifecycle operations judge the actual lane first (orchestrator ruling, tasks phase).**
    - For `start_implementation` and `start_review`, core's lifecycle logic runs against the work package's current lane in the fold before the stale-`from_lane` check, as `start_implementation_status` and `start_review_status` do against their own reduction. It gives one of three outcomes:
      - an incompatible holder → `WP_ALREADY_CLAIMED` / `claim_held_by_other_actor`;
      - a lane the start does not accept → `TRANSITION_REJECTED` / `start_rejected`;
      - a compatible holder in the target lane, which is core's zero-event no-op → a stale-`from_lane` refusal (`TRANSITION_REJECTED`, no URN; `oracle: protocol`). A conforming client sends nothing in that case.
    - A start from `planned` is a batch of two units (`planned → claimed`, then `claimed → in_progress`), each with `operation: start_implementation`.
    - A review re-claim is a `start_review` unit whose row is `for_review → in_review`. When the fold shows `in_review` held by a different actor with role `reviewer`, the answer is `WP_ALREADY_CLAIMED`.
16. **FR-010's allowlist names three packages.**
    - The three: event-log's existing `adapter.out.ledger` (lock files and the read ledger's torn-tail repair, never a status log), the new `adapter.out.statuslog`, and command-cli's `adapter.out.filesystem` (endpoint and service-lock files).
    - The rule's intent is that no other code writes files, and above all that no other code writes a status log.
17. **Maintainer rulings, 2026-11-08.** Each is a resolved Decision Moment under `decisions/`.
    - `operation` field kept in `1.0.1-SNAPSHOT` (decision 2; DM `01M6X2JWES41Y6PGF4EZ8M174K`).
    - A stale `from_lane` answers `TRANSITION_REJECTED` with the reason `urn:spec-kitty:write-refusal:stale_from_lane`, added to the contract's provisional codes as an events-side addition with no core equivalent; if a code proves impossible, a clear human-readable detail. A test covers the outcome (supersedes decision 5; DM `01M6X2Q4YK41J0Q03F55NAPS8Y`).
    - Torn-tail recovery after core #6195: an explicit repair command, spec-kitty/spec-kitty-events#222; the write path keeps refusing (C-006; DM `01M6X2Q7S8K2NET8XCE11KM7BA`).
    - ADR-0007 and ADR-0008 become Accepted in this mission's PR (accepted by the maintainer 2026-11-07; supersedes C-002's "status untouched"; DM `01M6X2QAWDFC27AREE1HJ1C4RH`).
    - `unreachable.json` stays as is; one issue per wire-unreachable reason: `workspace_context_required` #223, the evidence half of `implementation_evidence_required` #224 (DM `01M6X2QDYNTGZNG3A1HG4NJGMY`).
18. **WP08 review rulings (orchestrator, 2026-11-08; protocol-only outcomes found by the differential against core).**
    - R15: an unresolvable subtask roster on an unforced `in_progress -> for_review` (core raises `SubtaskRosterMissingError` / `SubtaskRosterResolutionError` and writes nothing) refuses `409 TRANSITION_REJECTED` / `implementation_evidence_required`, fail closed like an unresolvable dependency declaration.
    - R16: a `checkout_append` to a Mission whose primary `meta.json` records a non-blank `coordination_branch` answers `422 WRITE_LOCATION_UNVERIFIED` before any lock; core keys that Mission's lock with its mid8 and forbids a primary append, and `coordination_ref_commit` serves it from migration step 4 (decision 6).
    - R17: `--separate-git-dir` and submodule repositories refuse every write with `422` (FR-009's root-checkout rule); a deliberate divergence, since core writes status there.
    - R18: a work-package file larger than 16 MiB makes its facts unresolvable (fail closed: dependencies refuse `dependencies_unsatisfied`, the roster refuses under R15). Core reads files of any size; the bound keeps the read inside the Mission lock bounded.
    - R19: any explicit YAML tag (`!`, `!!x`, `!<…>`, handles, custom tags, on scalars or collections) and any `%YAML`/`%TAG` directive in a work package's frontmatter make its facts unresolvable (dependencies refuse `dependencies_unsatisfied`, the roster refuses under R15). A deliberate fail-closed divergence: core's ruamel round-trip loader accepts some tagged forms (`!!int 12`, `[!!str T001]`). Four review cycles showed that mirroring ruamel's tag semantics node by node keeps opening fail-open gaps; no real work-package file uses tags.
    - R20: a work package's frontmatter is also unresolvable (same refusals) when it contains a tab, U+0085, U+2028 or U+2029, any directive (with or without a following document), an anchor or alias token, an explicit document end `...`, or a further document. With R19 this restricts frontmatter to a plain YAML subset on which SnakeYAML (YAML 1.1 scanner) and core's ruamel (YAML 1.2) agree; none of the 8,392 unique real work-package frontmatters checked uses any of these constructs.
    - R21: a `:` value indicator inside a flow collection not followed by a space or line break also makes the frontmatter unresolvable (core's ruamel refuses most such forms and reads a few, e.g. `{'a':b}`; no real frontmatter uses a flow mapping). Recorded fail-closed residue where core reads and the service refuses: a block of just `:`, `<<` as a scalar value, `"\/"`, flow plains `[:x]`, `[a?]`, `[-?]`, `[::]`, complex map keys differing only in order, and map nesting at SnakeYAML's depth limit (49+).
    - More recorded fail-closed residue (WP08 review 7): timestamp keys equal as Java dates but distinct in Python, `=` vs `'='` inside complex keys, and Python-equal keys spread across merge sources or overridden by an explicit key. Every class, in both directions, is being classified by why core behaves as it does (maintainer, 2026-11-08: an intentional guard is matched; a core or stack accident gets a ruling and an issue).
19. **WP08 divergence audit and rulings R22–R29 (2026-11-08).** Maintainer ruling: every divergence from core is judged by why core behaves as it does. An intentional guard or a spec rule core follows is matched; an accident in core or in its stack (Python, ruamel) is not copied, but recorded as a ruling with an issue. Full evidence: `research/wp08-divergence-audit.md`.
    - Matched as intentional guards or spec rules: R15 roster resolution, `FrontmatterManager.read` rules (BOM, fences, empty block, textual duplicate keys), task-file globbing and ambiguity, own `meta.json` with a BOM, duplicate `<<` merge keys. R16 (coordination-routed Mission) mirrors core's intent.
    - R19–R21 are kept, reworded: part spec-mandated, part ruamel deviations from YAML 1.2 (core spec-kitty/spec-kitty#7444).
    - R22: every mapping key in work-package frontmatter, at any depth, must resolve to a string under the YAML 1.2 core schema. Null, bool, int, float (incl. `.nan`), timestamp, `=` and collection keys make the facts unresolvable. This replaces the Python-equality port: core compares keys with Python `==`/`hash` (`1 == 1.0 == true`, one shared NaN object, unhashable nested keys), a stack accident; YAML 1.2 §3.2.1.3 compares tag and canonical form. No real frontmatter uses a non-string key.
    - R23: a `|`/`>` block scalar without an indentation indicator whose first line after the header is non-empty and spaces-only makes the frontmatter unresolvable. ruamel's "more indented follow up line" check is stricter than §8.1.1.1 (#7444), so it is not ported.
    - R24: a status log containing a raw U+0085, U+2028, U+2029, VT, FF, FS, GS or RS is unreducible (500). Core's `read_events` splits with `str.splitlines()`, an accident (#7439); the service no longer models it.
    - R25: a subtask-roster entry that is not a string or an integer makes the roster unresolvable (R15). Core `str()`-renders other entries into ids no event can match (#7442).
    - R26: both task-file locators match the work-package id case-insensitively. Core's dependency locator is case-sensitive (#7443).
    - R27: a plain (unquoted) `=` scalar anywhere in the frontmatter makes its facts unresolvable. ruamel resolves it to the YAML 1.1 `value` type (#7444).
    - R28: `shell_pid` is valid when absent, null, empty, an integer, or a string of 1–4300 ASCII digits with an optional sign and surrounding whitespace; anything else makes the roster unresolvable. This replaces the Python `int()` port and its Unicode-14 digit table (a stack accident).
    - R29: a decimal integer scalar with more than 4300 digits anywhere in the frontmatter makes its facts unresolvable. Python refuses to construct it, which makes the whole file unreadable to core.
    - Kept, with core's acceptance judged a core bug: non-finite numbers (#7441) and mixed naive/aware session times (#7440) refused (decision 9); LF-only writes (#7441, decision 10). Kept as core's deliberate behaviour (the service refuses where core repairs or reads): torn tail (C-006), R17, R18. Where core misses duplicates the spec defines (map keys differing in order, same-instant timestamps, `=` vs `'='`), the service is spec-correct.
    - Own `meta.json` BOM policy inconsistency in core: #7445.
    - Implementation notes (WP08 cycle 8). Plain scalars resolve by the YAML 1.2.2 core schema through `Yaml12Schema`. SnakeYAML's own resolver is bypassed because it never resolves a scalar longer than 1024 characters, which would blur R29's 4300-digit boundary. Numeric spellings that only ruamel reads as numbers (`1_000`, signed `0b`/`0o`/`0x`, underscores in floats) are refused rather than copied. A timestamp is built as strictly as Python's `datetime`: an invalid one makes core's file unreadable, so it does here too. Corrections to the audit: (a) base-16 and base-8 integers are unlimited when parsed, but a roster entry is rendered with `str()`, so a hex or octal entry past 4300 decimal digits makes core's roster unresolvable; the service refuses it too (found by the differential, `bigint` set). (b) R24 splits rows on `\n` only, tolerating a trailing `\r`, so a bare `\r` row separator is now fail-closed residue (core's `splitlines()` splits there). (c) The cycle-6 core harness lacked core's `"subtasks" not in frontmatter` guard (`subtask_rows.py:315`); the cycle-8 harness `core3_facts.py` has it.

## 13. Post-tasks squad dispositions

Lenses: coverage and Definition-of-Done rigor (Opus) and brownfield feasibility (Sonnet), 2026-11-07. Every finding was folded into the tasks commit `9317d3b8` and plan `e746f501`, per rulings R1–R14 (orchestrator); none was dropped.

| Finding | Disposition |
|---|---|
| WP06 unit order contradicted decision 15 (MAJOR) | Folded: R4 |
| Production frontmatter reader never run against the corpus (MAJOR) | Folded: R5, `DiskMissionFactsParityTest` |
| Oracles could be finished without running core (MAJOR) | Folded: R3, a mandatory local `--check` at `512567ee` |
| Corpus under `fixtures/` breaks the manifest test (MAJOR) | Folded: R1, corpus moved to `conformance/status_transition_parity/` |
| `statuslog` breaks the root rule 7 of the read-service architecture (MAJOR) | Folded: R9 |
| Native FFM flags and placement of the metadata (MAJOR) | Folded: R10, metadata in command-cli |
| `PyIsoDateTime` test has no JSON parser and root-only coverage (MAJOR) | Folded: R11 |
| CI job in WP04 runs scripts of WPs it does not depend on | Folded: R2, new WP10 |
| NFR-004 complexity never checked | Folded: R6 (JaCoCo METHOD/COMPLEXITY ≤ 15, ruff C901) |
| WP08 coverage gaps, `detail` compare, invalid fixtures over HTTP | Folded: R5, R7 |
| FR-010 predicate lists differ | Folded: R8 |
| `OperationsTest` base row, examples, native smoke annotation row | Folded: R11 (FR-019's allowed exception recorded) |
| WP06 corpus floor, `FoldView`, start-from-planned, decision-15 cases | Folded: R4, R13 |
| `fromisoformat` subset between Python and Java | Folded: R12 |
| Placeholder acceptance matrix | Folded: R14 |
