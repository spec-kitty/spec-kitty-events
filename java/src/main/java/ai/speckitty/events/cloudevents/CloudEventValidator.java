package ai.speckitty.events.cloudevents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import tools.jackson.databind.JsonNode;

/**
 * Validates a CloudEvents row against an envelope profile and its type's data schema, using
 * the committed composed row schemas ({@code composed/rows} for the write profile,
 * {@code composed/read} for the read profile). The Java binding of
 * {@code spec_kitty_events.cloudevents.validate_cloudevent}; non-normative until the ADR 0001
 * cutover release.
 */
public final class CloudEventValidator {

    /** The envelope profile: rows a producer writes, or rows a reader meets. */
    public enum Profile {
        WRITE("composed/rows/"),
        READ("composed/read/");

        private final String directory;

        Profile(String directory) {
            this.directory = directory;
        }
    }

    /** One reason a row is not valid; {@code path} is a JSON Pointer ({@code ""} is the root). */
    public record Issue(String code, String path, String message, String keyword) {}

    private static final String MISSION_SOURCE = "/spec-kitty/missions/";
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final ConcurrentHashMap<String, Schema> CACHE = new ConcurrentHashMap<>();

    private CloudEventValidator() {}

    /** Every reason the row is not a valid CloudEvent of its registered type; empty when valid. */
    public static List<Issue> validate(JsonNode row, Profile profile) {
        if (row == null || !row.isObject()) {
            return List.of(new Issue("NOT_AN_OBJECT", "", "the row is not a JSON object", ""));
        }
        JsonNode type = row.get("type");
        JsonNode entry = type != null && type.isString() ? Contract.entry(type.asString()) : null;
        if (entry == null || !entry.get("type").asString().equals(type.asString())) {
            return List.of(new Issue("UNREGISTERED_TYPE", "/type", "type is not registered", ""));
        }
        String name = entry.get("name").asString();
        Schema schema = CACHE.computeIfAbsent(profile.directory + name, key ->
                SCHEMAS.getSchema(Contract.file(profile.directory + name + ".row.schema.json")));
        List<Issue> issues = new ArrayList<>();
        for (Error error : schema.validate(row)) {
            issues.add(new Issue("SCHEMA_VIOLATION", pointer(error.getInstanceLocation()), error.getMessage(), error.getKeyword()));
        }
        issues.sort(Comparator.comparing(Issue::path));
        JsonNode source = row.get("source");
        JsonNode mission = row.get("missionid");
        if (source != null && source.isString() && source.asString().startsWith(MISSION_SOURCE)
                && mission != null && mission.isString()
                && !source.asString().substring(MISSION_SOURCE.length()).equals(mission.asString())) {
            issues.add(new Issue("SOURCE_MISMATCH", "/missionid", "missionid is not the id in source", ""));
        }
        return issues;
    }

    private static String pointer(NodePath path) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.getNameCount(); i++) {
            out.append('/').append(path.getElement(i));
        }
        return out.toString();
    }
}
