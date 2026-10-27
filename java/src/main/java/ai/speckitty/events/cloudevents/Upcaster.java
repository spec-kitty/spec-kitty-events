package ai.speckitty.events.cloudevents;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The read-time upcaster: one legacy log row in, one CloudEvent (or the row unchanged) out.
 *
 * <p>The Java binding of {@code spec_kitty_events.cloudevents.upcast}, driven by the same
 * {@code registry.json} and pinned by the same corpus ({@code fixtures/upcast/cases}). Its
 * control flow mirrors the Python one step for step, so the first issue a row raises is the
 * same in both bindings. Deterministic, invents nothing, never mutates its input.
 */
public final class Upcaster {

    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_HYPHENATED =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_BARE = Pattern.compile("[0-9a-f]{32}", Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSION_AGGREGATE = Pattern.compile("mission/([^/]+)(?:/wp/([^/]+))?");
    private static final BigInteger CLOCK_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final Set<String> TRANSPORT_MARKERS =
            Set.of("event_type", "aggregate_id", "payload", "node_id", "build_id");
    private static final Set<String> TRANSPORT_MEMBERS = Set.of(
            "event_id", "event_type", "aggregate_id", "payload", "timestamp", "build_id", "node_id",
            "lamport_clock", "causation_id", "project_uuid", "project_slug", "correlation_id",
            "schema_version", "data_tier");
    private static final List<String> TRANSPORT_REQUIRED = List.of(
            "event_id", "event_type", "aggregate_id", "timestamp", "build_id", "node_id", "project_uuid",
            "correlation_id", "schema_version");
    private static final Set<String> IMPLIED_BY_TYPE = Set.of("aggregate_type", "schema_version", "row_version");
    private static final Pattern TIME = Pattern.compile(
            Contract.file("envelope.read.schema.json").get("properties").get("time").get("pattern").asString());

    private Upcaster() {}

    // ------------------------------------------------------------------ entry points
    /** Read one parsed row permissively. */
    public static UpcastResult upcast(JsonNode row, UpcastContext context) {
        return upcast(row, context, false, null);
    }

    /**
     * Read one parsed row. Permissive: an unmappable row comes back unchanged with the named
     * issue. Strict: the same row throws {@link UpcastException}. A CloudEvent row is a
     * pass-through in both modes.
     */
    public static UpcastResult upcast(JsonNode row, UpcastContext context, boolean strict, byte[] originalLine) {
        UpcastContext ctx = context == null ? UpcastContext.NONE : context;
        Detection detection = detect(row);
        UpcastIssue issue;
        if (detection.code != null) {
            issue = new UpcastIssue(detection.code, detection.code.name());
        } else {
            try {
                JsonNode event = map(detection, (ObjectNode) row, ctx);
                return new UpcastResult("mapped", event, detection.form, row, originalLine, List.of());
            } catch (Failure failure) {
                issue = failure.issue;
            }
        }
        if (strict && issue.code() != IssueCode.ALREADY_CLOUDEVENT) {
            throw new UpcastException(issue);
        }
        return new UpcastResult("passthrough", row, detection.form, row, originalLine, List.of(issue));
    }

    /** Read one log line (its bytes as written). */
    public static UpcastResult upcastLine(byte[] line, UpcastContext context, boolean strict) {
        JsonNode row;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line))
                    .toString();
            row = Contract.MAPPER.readTree(text);
            if (row == null || row.isMissingNode()) {
                throw new IllegalArgumentException("empty line");
            }
        } catch (CharacterCodingException | JacksonException | IllegalArgumentException e) {
            UpcastIssue issue = new UpcastIssue(IssueCode.NOT_JSON, "the line is not a JSON value");
            if (strict) {
                throw new UpcastException(issue);
            }
            return new UpcastResult("passthrough", null, "none", null, line, List.of(issue));
        }
        return upcast(row, context, strict, line);
    }

    // ------------------------------------------------------------------ detection
    private record Detection(String form, JsonNode entry, IssueCode code) {}

    private static Detection detect(JsonNode row) {
        if (row == null || !row.isObject()) {
            return new Detection("none", null, IssueCode.NOT_AN_OBJECT);
        }
        if (row.has("specversion")) {
            return new Detection("cloudevent", null, IssueCode.ALREADY_CLOUDEVENT);
        }
        JsonNode registry = Contract.registry();
        JsonNode shapes = registry.get("legacy_shapes");
        if (TRANSPORT_MARKERS.stream().allMatch(row::has)) {
            JsonNode eventType = row.get("event_type");
            JsonNode entry = null;
            if (eventType.isString()) {
                for (JsonNode candidate : registry.get("types")) {
                    JsonNode transport = candidate.get("transport");
                    if (!transport.isNull() && transport.get("event_type").asString().equals(eventType.asString())) {
                        entry = candidate;
                    }
                }
            }
            return entry == null
                    ? new Detection("transport_envelope", null, IssueCode.UNREGISTERED_TYPE)
                    : new Detection("transport_envelope", entry, null);
        }
        if (row.has("event_type") && row.has("payload")) {
            String shape = row.has("row_version") ? "journal_envelope"
                    : row.has("aggregate_type") ? "lifecycle_envelope"
                    : row.has("at") ? "decision_envelope" : null;
            if (shape == null) {
                return new Detection("none", null, IssueCode.UNRECOGNISED_ROW);
            }
            JsonNode discriminator = row.get("event_type");
            JsonNode entry = null;
            if (discriminator.isString()) {
                for (JsonNode candidate : registry.get("types")) {
                    JsonNode legacy = candidate.get("legacy");
                    if (!legacy.isNull() && shape.equals(legacy.get("shape").asString())
                            && discriminator.asString().equals(legacy.get("discriminator_value").asString())) {
                        entry = candidate;
                    }
                }
            }
            return entry == null ? new Detection(shape, null, IssueCode.UNREGISTERED_TYPE) : new Detection(shape, entry, null);
        }
        JsonNode lane = null;
        for (JsonNode entry : registry.get("types")) {
            JsonNode legacy = entry.get("legacy");
            if (legacy.isNull() || !"flat".equals(shapes.get(legacy.get("shape").asString()).get("form").asString())) {
                continue;
            }
            JsonNode constants = legacy.get("constants");
            if (constants.isEmpty()) {
                lane = entry;
                continue;
            }
            boolean all = true;
            for (Map.Entry<String, JsonNode> constant : constants.properties()) {
                if (!constant.getValue().equals(row.get(constant.getKey()))) {
                    all = false;
                }
            }
            if (all) {
                return new Detection("flat", entry, null);
            }
        }
        if (row.has("to_lane") && row.has("wp_id") && !row.has("event_type")) {
            return new Detection("flat", lane, null);
        }
        return new Detection("none", null, IssueCode.UNRECOGNISED_ROW);
    }

    // ------------------------------------------------------------------ member checks
    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient UpcastIssue issue;

        Failure(IssueCode code, String message) {
            super(message, null, false, false);
            this.issue = new UpcastIssue(code, message);
        }
    }

    private static Failure fail(IssueCode code, String message) {
        return new Failure(code, message);
    }

    private static boolean absent(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode();
    }

    /** Python truthiness of a JSON value. */
    private static boolean truthy(JsonNode value) {
        if (absent(value)) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.isIntegralNumber() ? value.bigIntegerValue().signum() != 0 : value.doubleValue() != 0.0;
        }
        if (value.isString()) {
            return !value.asString().isEmpty();
        }
        return !value.isEmpty();
    }

    private static boolean isInteger(JsonNode value) {
        return value != null && value.isIntegralNumber();
    }

    private static String string(JsonNode value, String member) {
        if (value == null || !value.isString() || value.asString().isEmpty()) {
            throw fail(IssueCode.INVALID_MEMBER, member + " must be a non-empty string");
        }
        return value.asString();
    }

    static String normalizeEventId(String value) {
        if (value.length() == 26 && ULID.matcher(value).matches()) {
            return value.toUpperCase(Locale.ROOT);
        }
        if (value.length() == 36 && UUID_HYPHENATED.matcher(value).matches()) {
            return value.toLowerCase(Locale.ROOT);
        }
        if (value.length() == 32 && UUID_BARE.matcher(value).matches()) {
            String h = value.toLowerCase(Locale.ROOT);
            return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-"
                    + h.substring(16, 20) + "-" + h.substring(20);
        }
        return null;
    }

    private static String eventId(JsonNode value, String member) {
        String normalized = value != null && value.isString() ? normalizeEventId(value.asString()) : null;
        if (normalized == null) {
            throw fail(IssueCode.INVALID_ID, member + " must be a ULID or a UUID");
        }
        return normalized;
    }

    private static String missionId(JsonNode value, String member) {
        if (value == null || !value.isString() || !ULID.matcher(value.asString()).matches()) {
            throw fail(IssueCode.INVALID_ID, member + " must be a ULID");
        }
        return value.asString().toUpperCase(Locale.ROOT);
    }

    private static String time(JsonNode value, String member) {
        if (value == null || !value.isString() || !TIME.matcher(value.asString()).lookingAt()) {
            throw fail(IssueCode.INVALID_TIME, member + " must be an RFC 3339 timestamp");
        }
        return value.asString();
    }

    private static String projectUuid(JsonNode value, String member) {
        String text = string(value, member);
        if (!UUID_HYPHENATED.matcher(text).matches() && !UUID_BARE.matcher(text).matches()) {
            throw fail(IssueCode.INVALID_MEMBER, member + " must be a UUID");
        }
        return text;
    }

    private static ObjectNode object(JsonNode value, String member) {
        if (value == null || !value.isObject()) {
            throw fail(IssueCode.INVALID_MEMBER, member + " must be an object");
        }
        return (ObjectNode) value.deepCopy();
    }

    private static void require(JsonNode row, List<String> members) {
        List<String> missing = members.stream().filter(m -> absent(row.get(m))).toList();
        if (!missing.isEmpty()) {
            throw fail(IssueCode.MISSING_MEMBER, "missing: " + String.join(", ", missing));
        }
    }

    private static void noStrays(JsonNode row, Set<String> allowed) {
        List<String> strays = row.propertyNames().stream().filter(name -> !allowed.contains(name)).sorted().toList();
        if (!strays.isEmpty()) {
            throw fail(IssueCode.UNEXPECTED_MEMBER, "not mapped by this form: " + String.join(", ", strays));
        }
    }

    // ------------------------------------------------------------------ the mapping state
    private record Candidate(String source, String value) {}

    private static final class Draft {
        final JsonNode entry;
        final UpcastContext context;
        final ObjectNode attributes = Contract.MAPPER.createObjectNode();
        ObjectNode data = Contract.MAPPER.createObjectNode();
        final List<Candidate> missionIds = new ArrayList<>();
        final List<Candidate> slugs = new ArrayList<>();
        final List<String> aggregateSlugs = new ArrayList<>();
        String subject;
        String aggregate;
        String project;

        Draft(JsonNode entry, UpcastContext context) {
            this.entry = entry;
            this.context = context;
        }

        void lift(JsonNode item, JsonNode value) {
            String attribute = item.get("attribute").asString();
            String member = item.get("member").asString();
            switch (attribute) {
                case "type" -> { }
                case "id" -> attributes.put("id", eventId(value, member));
                case "time" -> attributes.put("time", time(value, member));
                case "missionid" -> missionIds.add(new Candidate(member, missionId(value, member)));
                case "missionslug" -> slugs.add(new Candidate(member, string(value, member)));
                case "subject" -> subject = string(value, member);
                default -> {
                    if (item.has("projection")) {
                        projectActor(item, value);
                    } else {
                        attributes.put(attribute, string(value, member));
                    }
                }
            }
        }

        private void projectActor(JsonNode item, JsonNode value) {
            String member = item.get("member").asString();
            JsonNode identity;
            if (value != null && value.isObject()) {
                data.set(item.get("binding_member").asString(), value.deepCopy());
                if ("retrospective_actor_id".equals(item.get("projection").asString())) {
                    identity = value.get("id");
                } else {
                    identity = truthy(value.get("tool")) ? value.get("tool") : value.get("role");
                }
            } else if (value != null && value.isString()) {
                identity = value;
            } else {
                throw fail(IssueCode.INVALID_MEMBER, member + " must be a string or an object");
            }
            if (!absent(identity) && !identity.isString()) {
                throw fail(IssueCode.INVALID_MEMBER, member + " identity must be a string");
            }
            if (truthy(identity)) {
                attributes.put(item.get("attribute").asString(), identity.asString());
            }
        }
    }

    private static String one(List<Candidate> candidates, IssueCode code, String what) {
        Set<String> values = new LinkedHashSet<>();
        candidates.forEach(c -> values.add(c.value()));
        if (values.size() > 1) {
            throw fail(code, what + " disagrees: " + candidates);
        }
        return values.isEmpty() ? null : values.iterator().next();
    }

    private static boolean accounted(String aggregate, String missionId, List<String> slugs, String subject) {
        Set<String> heads = new LinkedHashSet<>(List.of(missionId, "mission/" + missionId));
        for (String slug : slugs) {
            heads.add(slug);
            heads.add("mission/" + slug);
        }
        Set<String> options = new LinkedHashSet<>(heads);
        if (subject != null) {
            options.add(subject);
            for (String head : heads) {
                if (head.startsWith("mission/")) {
                    options.add(head + "/wp/" + subject);
                }
            }
        }
        return options.contains(aggregate);
    }

    private static JsonNode finish(Draft draft) {
        JsonNode entry = draft.entry;
        UpcastContext context = draft.context;
        if (context.missionid() != null) {
            draft.missionIds.add(new Candidate("context", missionId(Contract.MAPPER.getNodeFactory().stringNode(context.missionid()), "context.missionid")));
        }
        if (context.missionslug() != null) {
            draft.slugs.add(new Candidate("context", string(Contract.MAPPER.getNodeFactory().stringNode(context.missionslug()), "context.missionslug")));
        }
        String missionId = one(draft.missionIds, IssueCode.MISSION_ID_CONFLICT, "mission id");
        String slug = one(draft.slugs, IssueCode.MISSION_SLUG_CONFLICT, "mission slug");
        String project = draft.project;
        if (project == null && context.projectuuid() != null) {
            project = projectUuid(Contract.MAPPER.getNodeFactory().stringNode(context.projectuuid()), "context.projectuuid");
        }
        String subject = draft.subject;
        String aggregate = draft.aggregate;
        String scope = entry.get("scope").asString();
        String subjectRule = entry.get("subject").asString();
        String source;
        if ("project".equals(scope)) {
            if (project == null) {
                throw fail(IssueCode.MISSION_ID_MISSING, "a project-scoped type needs a project uuid");
            }
            if (aggregate != null && !aggregate.equals(project) && !aggregate.equals("project/" + project)) {
                throw fail(IssueCode.AGGREGATE_UNMAPPABLE, "aggregate is not the project");
            }
            source = "/spec-kitty/projects/" + project;
            subject = null;
            missionId = null;
            slug = null;
        } else if (missionId != null) {
            List<String> slugs = new ArrayList<>();
            if (slug != null && !slug.isEmpty()) {
                slugs.add(slug);
            }
            slugs.addAll(draft.aggregateSlugs);
            if (aggregate != null && !accounted(aggregate, missionId, slugs, subject)) {
                throw fail(IssueCode.AGGREGATE_UNMAPPABLE, "aggregate is not this mission");
            }
            if ("none".equals(subjectRule) && subject != null) {
                throw fail(IssueCode.AGGREGATE_UNMAPPABLE, "a work-package aggregate on a mission-level type");
            }
            if ("wp_id".equals(subjectRule) && subject == null) {
                throw fail(IssueCode.MISSING_MEMBER, "a work-package type needs its work package");
            }
            source = "/spec-kitty/missions/" + missionId;
        } else {
            if (project == null) {
                throw fail(IssueCode.MISSION_ID_MISSING, "no mission id, and no project uuid to fall back to");
            }
            if (aggregate != null) {
                if (subject != null && !subject.equals(aggregate) && !aggregate.endsWith("/wp/" + subject)) {
                    throw fail(IssueCode.SUBJECT_CONFLICT, "aggregate does not end in the work package");
                }
                subject = aggregate;
            } else if (subject == null) {
                throw fail(IssueCode.MISSION_ID_MISSING, "no mission id, and no aggregate to use as subject");
            }
            source = "/spec-kitty/projects/" + project;
        }

        JsonNode registry = Contract.registry();
        ObjectNode event = Contract.MAPPER.createObjectNode();
        event.put("specversion", registry.get("specversion").asString());
        event.set("id", draft.attributes.remove("id"));
        event.put("source", source);
        event.put("type", entry.get("type").asString());
        event.set("time", draft.attributes.remove("time"));
        event.put("datacontenttype", registry.get("datacontenttype").asString());
        event.put("dataschema", entry.get("dataschema").asString());
        event.put("schemaversion", registry.get("schemaversion").asString());
        if (subject != null) {
            event.put("subject", subject);
        }
        if (missionId != null) {
            event.put("missionid", missionId);
        }
        if (slug != null) {
            event.put("missionslug", slug);
        }
        event.setAll(draft.attributes);
        event.set("data", draft.data);
        return event;
    }

    // ------------------------------------------------------------------ the forms
    private static JsonNode map(Detection detection, ObjectNode row, UpcastContext context) {
        return switch (detection.form) {
            case "transport_envelope" -> mapTransport(detection.entry, row, context);
            case "flat" -> mapFlat(detection.entry, row, context);
            default -> mapEnvelope(detection.form, detection.entry, row, context);
        };
    }

    private static JsonNode mapTransport(JsonNode entry, ObjectNode row, UpcastContext context) {
        JsonNode version = row.get("schema_version");
        if (version == null || !version.isString() || !"3.0.0".equals(version.asString())) {
            throw fail(IssueCode.UNSUPPORTED_SCHEMA_VERSION, "only the 3.0.0 transport envelope maps");
        }
        noStrays(row, TRANSPORT_MEMBERS);
        require(row, TRANSPORT_REQUIRED);
        Draft draft = new Draft(entry, context);
        draft.attributes.put("id", eventId(row.get("event_id"), "event_id"));
        draft.attributes.put("time", time(row.get("timestamp"), "timestamp"));
        draft.attributes.put("buildid", string(row.get("build_id"), "build_id"));
        draft.attributes.put("nodeid", string(row.get("node_id"), "node_id"));
        JsonNode clock = row.get("lamport_clock");
        if (!absent(clock)) {
            if (!isInteger(clock)) {
                throw fail(IssueCode.INVALID_MEMBER, "lamport_clock must be an integer");
            }
            BigInteger value = clock.bigIntegerValue();
            if (value.signum() < 0 || value.compareTo(CLOCK_MAX) > 0) {
                throw fail(IssueCode.CLOCK_OUT_OF_RANGE, "lamport_clock is outside 0.." + Integer.MAX_VALUE);
            }
            draft.attributes.put("lamportclock", value.intValueExact());
        }
        if (!absent(row.get("causation_id"))) {
            draft.attributes.put("causationid", eventId(row.get("causation_id"), "causation_id"));
        }
        draft.attributes.put("correlationid", eventId(row.get("correlation_id"), "correlation_id"));
        draft.project = projectUuid(row.get("project_uuid"), "project_uuid");
        draft.attributes.put("projectuuid", draft.project);
        if (!absent(row.get("project_slug"))) {
            draft.attributes.put("projectslug", string(row.get("project_slug"), "project_slug"));
        }
        JsonNode tier = row.get("data_tier");
        if (!absent(tier)) {
            if (!isInteger(tier) || tier.bigIntegerValue().compareTo(BigInteger.ZERO) < 0
                    || tier.bigIntegerValue().compareTo(BigInteger.valueOf(4)) > 0) {
                throw fail(IssueCode.INVALID_MEMBER, "data_tier must be an integer 0..4");
            }
            draft.attributes.put("datatier", tier.intValue());
        }

        ObjectNode payload = object(row.get("payload"), "payload");
        JsonNode transport = entry.get("transport");
        for (JsonNode item : transport.get("lifted")) {
            JsonNode value = payload.remove(item.get("member").asString());
            if (!absent(value)) {
                draft.lift(item, value);
            }
        }
        for (Map.Entry<String, JsonNode> alias : transport.get("lane_aliases").properties()) {
            JsonNode current = payload.get(alias.getKey());
            if (current != null && current.isString() && alias.getValue().has(current.asString())) {
                payload.set(alias.getKey(), alias.getValue().get(current.asString()).deepCopy());
            }
        }
        draft.data.setAll(payload);
        JsonNode payloadSlug = payload.get("mission_slug");
        if (payloadSlug != null && payloadSlug.isString()) {
            draft.aggregateSlugs.add(payloadSlug.asString());
        }

        String aggregate = string(row.get("aggregate_id"), "aggregate_id");
        Matcher match = MISSION_AGGREGATE.matcher(aggregate);
        if (match.matches() && ULID.matcher(match.group(1)).matches()) {
            draft.missionIds.add(new Candidate("aggregate_id", match.group(1).toUpperCase(Locale.ROOT)));
            String wp = match.group(2);
            if (wp != null) {
                if (!"wp_id".equals(entry.get("subject").asString())) {
                    throw fail(IssueCode.AGGREGATE_UNMAPPABLE, "a work-package aggregate on a mission-level type");
                }
                if (draft.subject != null && !draft.subject.equals(wp)) {
                    throw fail(IssueCode.SUBJECT_CONFLICT, "aggregate wp vs payload wp_id");
                }
                draft.subject = wp;
            }
        } else {
            draft.aggregate = aggregate;
        }
        return finish(draft);
    }

    private static JsonNode mapEnvelope(String form, JsonNode entry, ObjectNode row, UpcastContext context) {
        JsonNode shape = Contract.registry().get("legacy_shapes").get(form);
        String payloadMember = shape.get("payload_member").asString();
        Set<String> allowed = new LinkedHashSet<>(shape.get("members").propertyNames());
        allowed.add(shape.get("discriminator_member").asString());
        allowed.add(payloadMember);
        noStrays(row, allowed);
        List<String> required = new ArrayList<>();
        shape.get("required").forEach(r -> required.add(r.asString()));
        require(row, required);
        Draft draft = new Draft(entry, context);
        Set<String> lifted = new LinkedHashSet<>();
        for (JsonNode item : entry.get("lifted")) {
            lifted.add(item.get("member").asString());
            draft.lift(item, row.get(item.get("member").asString()));
        }
        draft.data = object(row.get(payloadMember), payloadMember);
        for (Map.Entry<String, JsonNode> member : row.properties()) {
            String name = member.getKey();
            JsonNode value = member.getValue();
            if (lifted.contains(name) || IMPLIED_BY_TYPE.contains(name) || name.equals(payloadMember) || absent(value)) {
                continue;
            }
            switch (name) {
                case "aggregate_id" -> {
                    draft.aggregate = string(value, name);
                    if (ULID.matcher(draft.aggregate).matches()) {
                        draft.missionIds.add(new Candidate(name, draft.aggregate.toUpperCase(Locale.ROOT)));
                        draft.aggregate = null;
                    }
                }
                case "project_uuid" -> {
                    draft.project = projectUuid(value, name);
                    draft.attributes.put("projectuuid", draft.project);
                }
                case "project_slug" -> draft.attributes.put("projectslug", string(value, name));
                default -> throw fail(IssueCode.UNEXPECTED_MEMBER, "no mapping for " + name);
            }
        }
        return finish(draft);
    }

    private static JsonNode mapFlat(JsonNode entry, ObjectNode row, UpcastContext context) {
        ObjectNode members = row.deepCopy();
        for (String key : entry.get("legacy").get("constants").propertyNames()) {
            members.remove(key);
        }
        if (members.has("feature_slug") && "lane.transitioned.v1".equals(entry.get("name").asString())) {
            JsonNode legacySlug = members.remove("feature_slug");
            JsonNode current = members.get("mission_slug");
            if (!truthy(current)) {
                current = null;
            }
            // both are strings before they are compared (JSON 1, 1.0 and true are not slugs)
            string(legacySlug, "feature_slug");
            if (current != null) {
                string(current, "mission_slug");
            }
            if (current != null && !current.equals(legacySlug)) {
                throw fail(IssueCode.MISSION_SLUG_CONFLICT, "feature_slug and mission_slug disagree");
            }
            members.set("mission_slug", current == null ? legacySlug : current);
        }
        Draft draft = new Draft(entry, context);
        for (JsonNode item : entry.get("lifted")) {
            JsonNode value = members.remove(item.get("member").asString());
            if (absent(value)) {
                if (item.get("required").asBoolean()) {
                    throw fail(IssueCode.MISSING_MEMBER, "missing: " + item.get("member").asString());
                }
                continue;
            }
            draft.lift(item, value);
        }
        draft.data.setAll(members);
        return finish(draft);
    }
}
