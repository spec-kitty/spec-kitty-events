package ai.speckitty.events.cloudevents;

/** Thrown in strict use for a row that cannot be mapped. */
public final class UpcastException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient UpcastIssue issue;

    public UpcastException(UpcastIssue issue) {
        super(issue.code() + ": " + issue.message());
        this.issue = issue;
    }

    public UpcastIssue issue() {
        return issue;
    }

    public IssueCode code() {
        return issue.code();
    }
}
