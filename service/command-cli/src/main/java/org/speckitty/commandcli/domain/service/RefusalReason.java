package org.speckitty.commandcli.domain.service;

/** The 16 guard reasons of WriteRefusalReason, with the WriteRefusalCode each one binds to. */
public enum RefusalReason {
  UNKNOWN_LANE("unknown_lane", RefusalCode.TRANSITION_REJECTED),
  ILLEGAL_TRANSITION("illegal_transition", RefusalCode.TRANSITION_REJECTED),
  FORCE_REQUIRES_ACTOR_AND_REASON("force_requires_actor_and_reason", RefusalCode.TRANSITION_REJECTED),
  ACTOR_REQUIRED("actor_required", RefusalCode.TRANSITION_REJECTED),
  DEPENDENCIES_UNSATISFIED("dependencies_unsatisfied", RefusalCode.DEPENDENCIES_NOT_SATISFIED),
  WORKSPACE_CONTEXT_REQUIRED("workspace_context_required", RefusalCode.TRANSITION_REJECTED),
  IMPLEMENTATION_EVIDENCE_REQUIRED("implementation_evidence_required", RefusalCode.TRANSITION_REJECTED),
  REASON_REQUIRED("reason_required", RefusalCode.TRANSITION_REJECTED),
  REVIEW_REF_REQUIRED("review_ref_required", RefusalCode.TRANSITION_REJECTED),
  REVIEWER_APPROVAL_REQUIRED("reviewer_approval_required", RefusalCode.TRANSITION_REJECTED),
  REVIEW_EVIDENCE_INVALID("review_evidence_invalid", RefusalCode.TRANSITION_REJECTED),
  FORCE_NOTE_REQUIRED("force_note_required", RefusalCode.TRANSITION_REJECTED),
  DONE_EVIDENCE_INCOMPLETE("done_evidence_incomplete", RefusalCode.TRANSITION_REJECTED),
  CLAIM_HELD_BY_OTHER_ACTOR("claim_held_by_other_actor", RefusalCode.WP_ALREADY_CLAIMED),
  START_REJECTED("start_rejected", RefusalCode.TRANSITION_REJECTED),
  STALE_FROM_LANE("stale_from_lane", RefusalCode.TRANSITION_REJECTED);

  private static final String URN_PREFIX = "urn:spec-kitty:write-refusal:";

  private final String urn;
  private final RefusalCode code;

  RefusalReason(String suffix, RefusalCode code) {
    this.urn = URN_PREFIX + suffix;
    this.code = code;
  }

  public String urn() {
    return urn;
  }

  public RefusalCode code() {
    return code;
  }
}
