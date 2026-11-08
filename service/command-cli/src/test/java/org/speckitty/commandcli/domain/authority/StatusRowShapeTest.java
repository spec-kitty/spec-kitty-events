package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.speckitty.commandcli.domain.authority.StatusRowShape.Scope;
import org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowDecoder;

/**
 * PR #230 squad close-out: one test class per field-type class of {@link StatusRowShape}. Every case is a row shape that the
 * differential against core's reader (read_event_stream_from_text and the reducer, at 512567ee) settled: the {@code refused} sets are
 * shapes core raises on, the {@code accepted} sets are shapes core reads. The fold and the rule must agree on each.
 */
class StatusRowShapeTest {
  private static final String ID = "01J6XW9KQT7M0YB3N4R5CQZ2E1";
  private static final String REVIEW = "{\"reviewer\":\"r\",\"verdict\":\"approved\",\"reference\":\"x\"}";
  private static final String PRIOR = "{\"event_id\":\"00000000000000000000000001\",\"wp_id\":\"WP01\",\"from_lane\":\"genesis\",\"to_lane\":\"planned\","
      + "\"at\":\"2026-11-07T09:00:00+00:00\",\"actor\":\"a\",\"force\":false,\"execution_mode\":\"direct_repo\"}";
  private static final String CLAIM = "{\"event_id\":\"" + ID + "\",\"wp_id\":\"WP01\",\"from_lane\":\"planned\",\"to_lane\":\"claimed\","
      + "\"at\":\"2026-11-07T10:00:00+00:00\",\"actor\":\"a\",\"force\":false,\"execution_mode\":\"direct_repo\","
      + "\"reason\":null,\"review_ref\":null,\"evidence\":null,\"policy_metadata\":null}";
  private static final String NOTE = "{\"event_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2E5\",\"kind\":\"annotation\",\"wp_id\":\"WP01\","
      + "\"at\":\"2026-11-07T11:00:00+00:00\",\"actor\":\"a\",\"delta\":{\"note\":\"n\"}}";

  /** A refused or accepted case: the row, the field the rule names (null when accepted) and a label for the report. */
  private record Shape(String label, Map<String, Object> row, boolean annotation, String field) {
    @Override
    public String toString() {
      return label;
    }
  }

  // ---- transition rows --------------------------------------------------------------------------------------------------------

  static Stream<Shape> refused() {
    List<Shape> shapes = new ArrayList<>();
    for (String at : List.of("5", "null", "[1]", "{}", "true", "1.5")) shapes.add(transition("at", at, "at"));
    for (String id : List.of("[1]", "{}")) {
      shapes.add(transition("event_id", id, "event_id"));
      shapes.add(transition("wp_id", id, "wp_id"));
    }
    for (String metadata : List.of("\"x\"", "5", "1.5", "true", "[1]", "[[1]]")) shapes.add(transition("policy_metadata", metadata, "policy_metadata"));
    for (String slug : List.of("5", "1.5", "true", "[1]", "{\"a\":1}")) shapes.add(transition("mission_slug", slug, "mission_slug"));
    shapes.add(row("feature_slug 5 next to an empty mission_slug", CLAIM, "\"mission_slug\":\"\",\"feature_slug\":5", "mission_slug"));
    for (String result : List.of("\"x\"", "5", "[1]", "{\"reviewer\":\"r\"}", "{\"verdict\":\"v\",\"reference\":\"x\"}")) {
      shapes.add(transition("review_result", result, "review_result"));
    }
    for (String actor : List.of("{\"tool\":\"x\"}", "{\"role\":5,\"profile\":null,\"tool\":\"t\",\"model\":\"m\"}",
        "{\"role\":\"r\",\"profile\":\"p\",\"tool\":\"t\",\"model\":\"m\",\"profile_source\":\"bogus\"}",
        "{\"role\":\"r\",\"profile\":\"p\",\"tool\":\"t\",\"model\":\"m\",\"profile_source\":null}")) {
      shapes.add(transition("actor", actor, "actor"));
    }
    return shapes.stream();
  }

  static Stream<Shape> accepted() {
    List<Shape> shapes = new ArrayList<>();
    for (String metadata : List.of("null", "{}", "{\"a\":[[1]]}", "{\"agent\":5}", "\"\"", "0", "false", "[]")) {
      shapes.add(transition("policy_metadata", metadata, null));
    }
    shapes.add(row("a non-object policy_metadata on a row the reader never indexes it on",
        CLAIM.replace("\"from_lane\":\"planned\",\"to_lane\":\"claimed\"", "\"from_lane\":\"in_progress\",\"to_lane\":\"for_review\""),
        "\"policy_metadata\":[1]", null));
    for (String slug : List.of("\"\"", "0", "0.0", "-0.0", "false", "null", "[]", "{}", "\"mission\"", "\"../x\"")) shapes.add(transition("mission_slug", slug, null));
    shapes.add(row("a truthy mission_slug wins over a bad feature_slug", CLAIM, "\"mission_slug\":\"ok\",\"feature_slug\":5", null));
    for (String result : List.of("null", "\"\"", "0", "{}", "[]", "false", REVIEW, "{\"reviewer\":1,\"verdict\":2,\"reference\":3}")) {
      shapes.add(transition("review_result", result, null));
    }
    for (String scalar : List.of("5", "\"x\"", "true", "null", "1.5", "[1]", "{\"a\":1}")) {
      for (String field : List.of("force", "execution_mode", "reason", "reason_source", "review_ref")) shapes.add(transition(field, scalar, null));
    }
    for (String actor : List.of("5", "null", "[1]", "{\"role\":\"r\",\"profile\":\"p\",\"tool\":\"t\",\"model\":\"m\",\"profile_source\":\"flag\"}")) {
      shapes.add(transition("actor", actor, null));
    }
    for (String at : List.of("\"\"", "\"zzz\"", "\"2026-11-07\"")) shapes.add(transition("at", at, null));
    return shapes.stream();
  }

  @ParameterizedTest
  @MethodSource("refused")
  void aShapeCoreRefusesIsRefusedAtBothScopesNamingTheField(Shape shape) {
    Scope scope = Scope.READ_BACK;
    assertThat(refusal(shape, scope)).as(shape.label()).contains(shape.field());
    assertThat(refusal(shape, Scope.EDGE)).as(shape.label() + " at the edge").contains(shape.field());
    assertThat(refusal(shape, scope).orElseThrow()).doesNotContain("{").doesNotContain("[");
  }

  @ParameterizedTest
  @MethodSource("refused")
  void theFoldRefusesEveryShapeCoreRefuses(Shape shape) {
    assertThatThrownBy(() -> WriteFold.fold(List.of(map(PRIOR), shape.row()))).as(shape.label()).isInstanceOf(UnreducibleLog.class);
  }

  @ParameterizedTest
  @MethodSource("accepted")
  void aShapeCoreReadsIsAcceptedByTheRuleAndTheFold(Shape shape) {
    assertThat(refusal(shape, Scope.READ_BACK)).as(shape.label()).isEmpty();
    assertThat(refusal(shape, Scope.EDGE)).as(shape.label() + " at the edge").isEmpty();
    assertThat(WriteFold.fold(List.of(map(PRIOR), shape.row())).all()).as(shape.label()).isNotEmpty();
  }

  @ParameterizedTest
  @MethodSource("missingFields")
  void aMissingFieldIsTheReadBacksToRefuseAndNotTheEdges(String field) {
    Map<String, Object> missing = map(CLAIM);
    missing.remove(field);
    assertThat(StatusRowShape.transition(missing, Scope.READ_BACK)).contains(field);
    assertThat(StatusRowShape.transition(missing, Scope.EDGE)).isEmpty();
  }

  static Stream<String> missingFields() {
    return Stream.of("event_id", "wp_id", "from_lane", "to_lane", "at", "actor", "force", "execution_mode");
  }

  // ---- done evidence: the authority's guard owns it at the edge, the read-back owns its shape ---------------------------------

  static Stream<String> unreadableEvidence() {
    return Stream.of("\"x\"", "5", "1.5", "true", "[1]", "{\"a\":1}", "{\"review\":{}}", "{\"review\":null}", "{\"review\":false}",
        "{\"review\":[]}", "{\"review\":5}", "{\"review\":{\"reviewer\":\"r\",\"verdict\":\"v\"}}", "{\"repos\":[]}",
        evidence("\"repos\":null"), evidence("\"repos\":5"), evidence("\"repos\":\"x\""), evidence("\"repos\":[\"x\"]"),
        evidence("\"repos\":[{\"repo\":\"r\"}]"), evidence("\"repos\":{\"a\":1}"), evidence("\"verification\":null"),
        evidence("\"verification\":5"), evidence("\"verification\":\"x\""), evidence("\"verification\":[{\"command\":\"c\"}]"),
        evidence("\"verification\":[5]"));
  }

  static Stream<String> readableEvidence() {
    return Stream.of("null", "\"\"", "0", "false", "[]", "{}", "{\"review\":" + REVIEW + "}", evidence("\"repos\":[]"),
        evidence("\"repos\":\"\""), evidence("\"repos\":{}"), evidence("\"verification\":[]"),
        evidence("\"repos\":[{\"repo\":\"r\",\"branch\":\"b\",\"commit\":\"abc1234\",\"files_touched\":5}]"),
        evidence("\"verification\":[{\"command\":\"c\",\"result\":\"pass\",\"summary\":\"s\"}]"),
        "{\"review\":{\"reviewer\":1,\"verdict\":2,\"reference\":3},\"extra\":true}");
  }

  private static String evidence(String member) {
    return "{\"review\":" + REVIEW + "," + member + "}";
  }

  @ParameterizedTest
  @MethodSource("unreadableEvidence")
  void evidenceCoreCannotBuildIsRefusedByTheReadBackAndTheFold(String evidence) {
    Shape shape = transition("evidence", evidence, "evidence");
    assertThat(refusal(shape, Scope.READ_BACK)).as(evidence).contains("evidence");
    assertThat(refusal(shape, Scope.EDGE)).as("the done-evidence guard judges it first: " + evidence).isEmpty();
    assertThatThrownBy(() -> WriteFold.fold(List.of(map(PRIOR), shape.row()))).as(evidence).isInstanceOf(UnreducibleLog.class);
  }

  @ParameterizedTest
  @MethodSource("readableEvidence")
  void evidenceCoreBuildsIsReadBack(String evidence) {
    Shape shape = transition("evidence", evidence, null);
    assertThat(refusal(shape, Scope.READ_BACK)).as(evidence).isEmpty();
    assertThat(WriteFold.fold(List.of(map(PRIOR), shape.row())).all()).as(evidence).isNotEmpty();
  }

  // ---- annotation rows --------------------------------------------------------------------------------------------------------

  static Stream<Shape> refusedAnnotations() {
    List<Shape> shapes = new ArrayList<>();
    for (String id : List.of("\"x\"", "\"\"", "5", "null", "[1]", "\"01j6xw9kqt7m0yb3n4r5cqz2e5\"", "\"01J6XW9KQT7M0YB3N4R5CQZ2E\"",
        "\"01J6XW9KQT7M0YB3N4R5CQZ2EI\"", "\"01J6XW9KQT7M0YB3N4R5CQZ2E5\\n\\n\"", "\"01J6XW9KQT7M0YB3N4R5CQZ2E5\\r\"")) {
      shapes.add(annotation("event_id", id, "event_id"));
    }
    for (String delta : List.of("null", "\"x\"", "5", "[]", "[1]", "true", "\"\"")) shapes.add(annotation("delta", delta, "delta"));
    for (String actor : List.of("{}", "{\"a\":1}", "{\"tool\":\"x\"}", "{\"role\":5,\"profile\":null,\"tool\":\"t\",\"model\":\"m\"}",
        "{\"role\":\"r\",\"profile\":\"p\",\"tool\":\"t\",\"model\":\"m\",\"profile_source\":\"bogus\"}")) {
      shapes.add(annotation("actor", actor, "actor"));
    }
    shapes.addAll(delta("shell_pid", "delta.shell_pid", "\"x\"", "\"\"", "\"5.0\"", "\"0x1\"", "\"1e3\"", "\"5__0\"", "\"_5\"", "\"5_\"",
        "\"+\"", "\" \"", "\"\\u00b2\"", "[1]", "[]", "{}", "\"" + "9".repeat(4301) + "\""));
    shapes.addAll(delta("subtasks", "delta.subtasks", "\"x\"", "\"\"", "5", "true", "[1]", "[]", "{\"T1\":\"bogus\"}", "{\"T1\":5}",
        "{\"T1\":null}", "{\"T1\":true}", "{\"T1\":[\"done\"]}", "{\"T1\":{\"a\":1}}", "{\"T1\":\"Done\"}", "{\"T1\":\"SKIPPED\"}",
        "{\"T1\":\" done\"}", "{\"T1\":\"\"}"));
    shapes.addAll(delta("tracker_refs", "delta.tracker_refs", "5", "0", "1.5", "true", "false"));
    shapes.addAll(delta("tracker_refs_replace", "delta.tracker_refs_replace", "5", "0", "1.5", "true", "false", "[[1]]", "[{\"a\":1}]",
        "[\"a\",[1]]"));
    shapes.addAll(delta("review", "delta.review", "\"x\"", "\"\"", "5", "true", "false", "[]", "{}", "{\"at\":\"a\"}",
        "{\"at\":\"a\",\"actor\":\"b\",\"wp_id\":\"WP01\"}"));
    return shapes.stream();
  }

  static Stream<Shape> acceptedAnnotations() {
    List<Shape> shapes = new ArrayList<>();
    shapes.add(annotation("event_id", "\"01J6XW9KQT7M0YB3N4R5CQZ2E5\\n\"", null));
    for (String at : List.of("5", "null", "[1]", "{}", "true", "1.5", "\"\"", "\"zzz\"")) shapes.add(annotation("at", at, null));
    for (String wp : List.of("5", "null", "true", "[1]")) shapes.add(annotation("wp_id", wp, null));
    for (String actor : List.of("5", "null", "[1]", "\"\"", "{\"role\":null,\"profile\":null,\"tool\":null,\"model\":null}")) {
      shapes.add(annotation("actor", actor, null));
    }
    shapes.addAll(delta("shell_pid", null, "5", "0", "-3", "true", "false", "null", "1.5", "-0.0", "1e300", "\"5\"", "\" 5 \"", "\"+5\"",
        "\"-5\"", "\"5_0\"", "\"007\"", "\"\\u0665\"", "\"\\uff15\"", "\"5\\n\"", "\"\\u00a05\"", "\"" + "9".repeat(4300) + "\"",
        "99999999999999999999999999"));
    shapes.addAll(delta("subtasks", null, "null", "{}", "{\"T1\":\"done\"}", "{\"T1\":\"skipped\"}", "{\"T1\":\"not_applicable\"}",
        "{\"T1\":\"uninitialized\"}", "{\"T1\":\"planned\",\"T2\":\"canceled\"}"));
    shapes.addAll(delta("tracker_refs", null, "null", "\"abc\"", "\"\"", "[]", "[\"a\",1,null,true,[1],{\"a\":1}]", "{\"a\":1}", "{}"));
    shapes.addAll(delta("tracker_refs_replace", null, "null", "\"abc\"", "[]", "[\"a\",1,null,true,1.5]", "{\"a\":1}"));
    shapes.addAll(delta("review", null, "null", "{\"at\":\"a\",\"actor\":\"b\",\"wp_id\":\"WP01\",\"reason\":\"r\"}",
        "{\"at\":1,\"actor\":null,\"wp_id\":[1],\"reason\":{}}", "{\"at\":\"\",\"actor\":\"\",\"wp_id\":\"\",\"reason\":\"\"}"));
    for (String scalar : List.of("5", "\"x\"", "null", "[1]", "{\"a\":1}", "true")) {
      shapes.addAll(delta("agent", null, scalar));
      shapes.addAll(delta("note", null, scalar));
      shapes.addAll(delta("release_runtime_claim", null, scalar));
      shapes.addAll(delta("unknown_key", null, scalar));
    }
    return shapes.stream();
  }

  @ParameterizedTest
  @MethodSource("refusedAnnotations")
  void anAnnotationShapeCoreRefusesIsRefusedAtBothScopesAndByTheFold(Shape shape) {
    assertThat(refusal(shape, Scope.READ_BACK)).as(shape.label()).contains(shape.field());
    assertThat(refusal(shape, Scope.EDGE)).as(shape.label() + " at the edge").contains(shape.field());
    assertThat(refusal(shape, Scope.READ_BACK).orElseThrow()).doesNotContain("{").doesNotContain("[");
    assertThatThrownBy(() -> WriteFold.fold(List.of(map(PRIOR), shape.row()))).as(shape.label()).isInstanceOf(UnreducibleLog.class);
  }

  @ParameterizedTest
  @MethodSource("acceptedAnnotations")
  void anAnnotationShapeCoreReadsIsAcceptedByTheRuleAndTheFold(Shape shape) {
    assertThat(refusal(shape, Scope.READ_BACK)).as(shape.label()).isEmpty();
    assertThat(refusal(shape, Scope.EDGE)).as(shape.label() + " at the edge").isEmpty();
    // two of them, so a shape that only fails when annotations are ordered against each other (a null at) fails here too
    Map<String, Object> second = map(NOTE);
    second.put("event_id", "01J6XW9KQT7M0YB3N4R5CQZ2E6");
    assertThat(WriteFold.fold(List.of(map(PRIOR), shape.row(), second)).all()).as(shape.label()).isNotEmpty();
  }

  @ParameterizedTest
  @MethodSource("missingAnnotationFields")
  void aMissingAnnotationFieldIsTheReadBacksToRefuse(String field) {
    Map<String, Object> missing = map(NOTE);
    missing.remove(field);
    assertThat(StatusRowShape.annotation(missing, Scope.READ_BACK)).contains(field);
    assertThat(StatusRowShape.annotation(missing, Scope.EDGE)).isEmpty();
    assertThatThrownBy(() -> WriteFold.fold(List.of(map(PRIOR), missing))).isInstanceOf(UnreducibleLog.class);
  }

  static Stream<String> missingAnnotationFields() {
    return Stream.of("event_id", "wp_id", "at", "actor", "delta");
  }

  // ---- builders ---------------------------------------------------------------------------------------------------------------

  private static Optional<String> refusal(Shape shape, Scope scope) {
    return shape.annotation() ? StatusRowShape.annotation(shape.row(), scope) : StatusRowShape.transition(shape.row(), scope);
  }

  private static Shape transition(String field, String json, String refused) {
    return row(field + " = " + abbreviated(json), CLAIM, "\"" + field + "\":" + json, refused);
  }

  private static Shape annotation(String field, String json, String refused) {
    Map<String, Object> row = map(NOTE);
    row.put(field, parse(json));
    return new Shape("annotation " + field + " = " + abbreviated(json), row, true, refused);
  }

  private static List<Shape> delta(String key, String refusedField, String... values) {
    List<Shape> shapes = new ArrayList<>();
    for (String json : values) {
      Map<String, Object> row = map(NOTE);
      row.put("delta", map("{\"note\":\"n\",\"" + key + "\":" + json + "}"));
      shapes.add(new Shape("delta." + key + " = " + abbreviated(json), row, true, refusedField));
    }
    return shapes;
  }

  /** The base row with {@code members} (one {@code "name":value} member, or two joined by a comma) put over it. */
  private static Shape row(String label, String base, String members, String refused) {
    Map<String, Object> row = map(base);
    row.putAll(map("{" + members + "}"));
    return new Shape(label, row, false, refused);
  }

  private static String abbreviated(String json) {
    return json.length() > 24 ? json.substring(0, 24) + "..." : json;
  }

  private static Object parse(String json) {
    return StatusRowDecoder.parse(json);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(String json) {
    return new LinkedHashMap<>((Map<String, Object>) parse(json));
  }
}
