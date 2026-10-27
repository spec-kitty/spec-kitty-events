package ai.speckitty.events.cloudevents;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * The outcome of reading one row: {@code mapped} (the row is a new CloudEvent) or
 * {@code passthrough} (the row is the input, unchanged, and {@code issues} says why).
 * {@code originalLine} holds the bytes of a row read from a log line, so a writer can keep a
 * legacy row as written.
 */
public record UpcastResult(
        String outcome, JsonNode row, String form, JsonNode original, byte[] originalLine, List<UpcastIssue> issues) {

    public boolean mapped() {
        return "mapped".equals(outcome);
    }

    public List<String> codes() {
        return issues.stream().map(issue -> issue.code().name()).toList();
    }
}
