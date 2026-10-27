package ai.speckitty.events.cloudevents;

/** One named reason a row was not mapped. */
public record UpcastIssue(IssueCode code, String message) {}
