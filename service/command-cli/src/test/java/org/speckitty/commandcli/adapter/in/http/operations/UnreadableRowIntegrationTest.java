package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PR #230 squad (renata, MAJOR 1 and 2): the service never acknowledges a row it cannot read back from its own log, so no accepted
 * row can make the Mission's log unreadable and every later write refuse. Each case is checked over real HTTP: the refusal is a
 * contract {@code WriteRefusal}, nothing is written, and the next legal write to the same Mission still succeeds.
 */
class UnreadableRowIntegrationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID1 = "01J6XW9KQT7M0YB3N4R5CQZ2E1";
  private static final String ID2 = "01J6XW9KQT7M0YB3N4R5CQZ2E2";
  private static final String ID3 = "01J6XW9KQT7M0YB3N4R5CQZ2E3";
  private static final String FOUR_KEY_ACTOR = "{\"role\":\"implementer\",\"profile\":null,\"tool\":\"claude\",\"model\":null}";

  @Test
  void aRowNestedPastTheReadersLimitIsRefusedAtTheEdgeAndTheLogStaysWritable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String row = withMetadata(WriteRig.claimRow(ID1), "[".repeat(300) + "1" + "]".repeat(300));
      byte[] before = Files.readAllBytes(rig.log());
      assertRefusal(rig.post("unit", WriteRig.unit(row)), 400, null);
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertThat(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID2))).status()).isEqualTo(201);
    }
  }

  @Test
  void aRowNestedWithinTheReadersLimitIsAcknowledgedAndReadBack() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      // an object, because core indexes a claim's policy_metadata: nesting inside it is the reader's limit, not its type
      String row = withMetadata(WriteRig.claimRow(ID1), "{\"a\":" + "[".repeat(200) + "1" + "]".repeat(200) + "}");
      assertThat(rig.post("unit", WriteRig.unit(row)).status()).isEqualTo(201);
      RawClient.Reply next = rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(ID2, "WP01", "n")));
      assertThat(next.status()).as(next.text()).isEqualTo(201);
    }
  }

  @Test
  void anIntegerTheReaderAcceptsIsAcceptedAtTheEdgeAndOneItRefusesIsNot() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      assertThat(rig.post("unit", WriteRig.unit(withMetadata(WriteRig.claimRow(ID1), "{\"n\":" + "9".repeat(2000) + "}"))).status()).isEqualTo(201);
      assertThat(rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(ID2, "WP01", "n"))).status()).isEqualTo(201);
      byte[] before = Files.readAllBytes(rig.log());
      assertRefusal(rig.post("unit", WriteRig.unit(withMetadata(claimFrom("in_progress", ID3), "{\"n\":" + "9".repeat(4301) + "}"))), 400, null);
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void aStructuredActorTheFoldCannotDecodeIsRefusedAndTheLogStaysWritable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      byte[] before = Files.readAllBytes(rig.log());
      for (String actor : List.of("{\"tool\":\"x\"}", FOUR_KEY_ACTOR.replace("\"claude\"", "7"))) {
        assertRefusal(rig.post("unit", WriteRig.unit(withActor(WriteRig.claimRow(ID1), actor))), 400, null);
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      }
      assertThat(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID2))).status()).isEqualTo(201);
    }
  }

  @Test
  void aStructuredActorTheFoldDecodesIsAcknowledgedAndReadBack() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      assertThat(rig.post("unit", WriteRig.unit(withActor(WriteRig.claimRow(ID1), FOUR_KEY_ACTOR))).status()).isEqualTo(201);
      RawClient.Reply next = rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(ID2, "WP01", "n")));
      assertThat(next.status()).as(next.text()).isEqualTo(201);
    }
  }

  @Test
  void aBatchWithAnUnreadableClaimAndASecondUnitIsACodedRefusalNamingTheUnit() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      byte[] before = Files.readAllBytes(rig.log());
      String bad = WriteRig.unit("WP01", null, withActor(WriteRig.claimRow(ID1), "{\"tool\":\"x\"}"));
      String second = WriteRig.unit("WP02", null, WriteRig.transitionRow(ID2, "WP02", "genesis", "planned", "java-jenny"));
      RawClient.Reply reply = rig.post("batch", WriteRig.batch(bad, second));
      assertRefusal(reply, 400, "#/units/0");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertThat(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID3))).status()).isEqualTo(201);
    }
  }

  // ---- PR #230 squad close-out: a row core's reader refuses is never acknowledged, whatever the field's type ---------------------

  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final String EVIDENCE_REVIEW = "{\"reviewer\":\"r\",\"verdict\":\"approved\",\"reference\":\"x\"}";

  @Test
  void aTransitionFieldOfATypeCoreCannotReadIsRefusedAtTheEdgeAndTheLogStaysWritable() throws Exception {
    List<String[]> members = List.of(
        member("at", "5"), member("at", "null"), member("at", "[1]"), member("at", "{}"), member("at", "true"), member("at", "1.5"),
        member("policy_metadata", "\"x\""), member("policy_metadata", "5"), member("policy_metadata", "[1]"),
        member("policy_metadata", "[[1]]"), member("policy_metadata", "true"), member("policy_metadata", "1.5"),
        member("mission_slug", "5"), member("mission_slug", "[1]"), member("mission_slug", "{\"a\":1}"),
        member("review_result", "\"x\""), member("review_result", "5"), member("review_result", "{\"reviewer\":\"r\"}"),
        member("actor", "{\"role\":\"r\",\"profile\":\"p\",\"tool\":\"t\",\"model\":\"m\",\"profile_source\":\"bogus\"}"));
    try (WriteRig rig = WriteRig.start()) {
      for (int i = 0; i < members.size(); i++) {
        String row = withMember(WriteRig.claimRow(ID1), members.get(i)[0], members.get(i)[1]);
        assertRefusedAndLogWritable(rig, "unit", WriteRig.unit(row), null, i);
      }
    }
  }

  @Test
  void aTransitionFieldCoreReadsAsAnyTypeIsAcknowledgedAndTheLogStaysReadable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String row = WriteRig.claimRow(ID1).replace("\"force\":false", "\"force\":\"yes\"").replace("\"reason\":null", "\"reason\":5")
          .replace("\"review_ref\":null", "\"review_ref\":[1]").replace("\"policy_metadata\":null", "\"policy_metadata\":{\"agent\":5}");
      assertThat(rig.post("unit", WriteRig.unit(row)).status()).isEqualTo(201);
      assertThat(rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(id(1), "WP01", "after"))).status()).isEqualTo(201);
    }
  }

  @Test
  void aPolicyMetadataThatIsNotAnObjectIsAcceptedOnARowTheReaderNeverIndexesItOn() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      assertThat(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1))).status()).isEqualTo(201);
      String start = WriteRig.transitionRow(ID2, "WP01", "claimed", "in_progress", "java-jenny").replace("\"policy_metadata\":null", "\"policy_metadata\":[1]");
      RawClient.Reply reply = rig.post("unit", WriteRig.unit("WP01", "start_implementation", start));
      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
    }
  }

  @Test
  void anAnnotationDeltaCoreCannotCoerceIsRefusedAtTheEdgeAndTheLogStaysWritable() throws Exception {
    List<String> deltas = List.of("{\"shell_pid\":\"x\"}", "{\"shell_pid\":\"5.0\"}", "{\"shell_pid\":[1]}", "{\"shell_pid\":{}}",
        "{\"subtasks\":\"x\"}", "{\"subtasks\":5}", "{\"subtasks\":[1]}", "{\"subtasks\":{\"T1\":\"bogus\"}}", "{\"subtasks\":{\"T1\":5}}",
        "{\"subtasks\":{\"T1\":null}}", "{\"tracker_refs\":5}", "{\"tracker_refs\":true}", "{\"tracker_refs_replace\":1.5}",
        "{\"tracker_refs_replace\":[[1]]}", "{\"tracker_refs_replace\":[{\"a\":1}]}", "{\"review\":\"x\"}", "{\"review\":{}}",
        "{\"review\":{\"at\":\"a\",\"actor\":\"b\"}}");
    try (WriteRig rig = WriteRig.start()) {
      for (int i = 0; i < deltas.size(); i++) {
        String row = annotationRow(id(100 + i), "\"java-jenny\"", deltas.get(i).replace("}", ",\"note\":\"n\"}"), "\"2026-11-07T10:00:00+00:00\"");
        assertRefusedAndLogWritable(rig, "annotation", WriteRig.annotation("WP01", row), null, i);
      }
    }
  }

  @Test
  void anAnnotationDeltaThatIsNotAnObjectAndAnUnreadableAnnotationActorAreRefused() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      List<String> rows = new java.util.ArrayList<>();
      for (String delta : List.of("null", "\"x\"", "5", "[1]")) rows.add(annotationRow(id(200 + rows.size()), "\"java-jenny\"", delta, "\"2026-11-07T10:00:00+00:00\""));
      for (String actor : List.of("{\"tool\":\"x\"}", "{\"role\":5,\"profile\":null,\"tool\":\"t\",\"model\":\"m\"}")) {
        rows.add(annotationRow(id(220 + rows.size()), actor, "{\"note\":\"n\"}", "\"2026-11-07T10:00:00+00:00\""));
      }
      for (int i = 0; i < rows.size(); i++) assertRefusedAndLogWritable(rig, "annotation", WriteRig.annotation("WP01", rows.get(i)), null, i);
    }
  }

  @Test
  void anAnnotationFieldCoreCoercesWithStrIsAcknowledgedAndTheLogStaysReadable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      int next = 300;
      for (String at : List.of("5", "null", "[1]", "true", "1.5")) {
        String row = annotationRow(id(next++), "\"java-jenny\"", "{\"note\":\"n\"}", at);
        RawClient.Reply reply = rig.post("annotation", WriteRig.annotation("WP01", row));
        assertThat(reply.status()).as(at + ": " + reply.text()).isEqualTo(201);
      }
      String row = annotationRow(id(next++), "\"java-jenny\"", "{\"shell_pid\":\" 5 \",\"tracker_refs\":\"abc\",\"subtasks\":{\"T1\":\"not_applicable\"}}", "\"2026-11-07T12:00:00+00:00\"");
      assertThat(rig.post("annotation", WriteRig.annotation("WP01", row)).status()).isEqualTo(201);
      assertThat(rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(id(next), "WP01", "after"))).status()).isEqualTo(201);
    }
  }

  @Test
  void aRowMissingAFieldCoreRequiresIsRefusedByTheReadBackAndTheLogStaysWritable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      int next = 400;
      for (String field : List.of("at", "actor", "delta")) {
        String row = WriteRig.annotationRow(id(next++), "WP01", "n");
        String without = field.equals("delta") ? row.replace(",\"delta\":{\"note\":\"n\"}", "")
            : row.replaceFirst(",\"" + field + "\":\"[^\"]*\"", "");
        assertThat(without).doesNotContain("\"" + field + "\":");
        assertRefusedAndLogWritable(rig, "annotation", WriteRig.annotation("WP01", without), null, next);
      }
    }
  }

  @Test
  void doneEvidenceCoreCannotBuildIsRefusedAfterTheAuthorityAndTheLogStaysWritable() throws Exception {
    List<String> evidences = List.of(
        "{\"review\":" + EVIDENCE_REVIEW + ",\"repos\":5}", "{\"review\":" + EVIDENCE_REVIEW + ",\"repos\":null}",
        "{\"review\":" + EVIDENCE_REVIEW + ",\"repos\":[\"x\"]}", "{\"review\":" + EVIDENCE_REVIEW + ",\"repos\":[{\"repo\":\"r\"}]}",
        "{\"review\":" + EVIDENCE_REVIEW + ",\"verification\":null}", "{\"review\":" + EVIDENCE_REVIEW + ",\"verification\":\"x\"}",
        "{\"review\":" + EVIDENCE_REVIEW + ",\"verification\":[{\"command\":\"c\"}]}");
    try (WriteRig rig = WriteRig.start()) {
      for (int i = 0; i < evidences.size(); i++) {
        String row = WriteRig.claimRow(ID1).replace("\"evidence\":null", "\"evidence\":" + evidences.get(i));
        assertRefusedAndLogWritable(rig, "unit", WriteRig.unit(row), null, i);
      }
      String readable = WriteRig.claimRow(ID2).replace("\"evidence\":null", "\"evidence\":{\"review\":" + EVIDENCE_REVIEW + ",\"repos\":[]}");
      assertThat(rig.post("unit", WriteRig.unit(readable)).status()).isEqualTo(201);
    }
  }

  @Test
  void aBatchUnitWithAShapeCoreRefusesIsNamedAndNothingOfTheBatchIsWritten() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String first = WriteRig.unit("WP01", null, WriteRig.claimRow(ID1));
      String second = WriteRig.unit("WP02", null, WriteRig.transitionRow(ID2, "WP02", "genesis", "planned", "java-jenny").replace("\"at\":\"2026-11-07T10:00:00+00:00\"", "\"at\":5"));
      byte[] before = Files.readAllBytes(rig.log());
      assertRefusal(rig.post("batch", WriteRig.batch(first, second)), 400, "#/units/1");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertThat(rig.post("batch", WriteRig.batch(first, WriteRig.unit("WP02", null, WriteRig.transitionRow(ID2, "WP02", "genesis", "planned", "java-jenny")))).status())
          .isEqualTo(201);
    }
  }

  @Test
  void aRefusalDetailNamesTheFieldAndNeverItsValue() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(withMetadata(WriteRig.claimRow(ID1), "\"s3cr3t-value\"")));
      assertRefusal(reply, 400, null);
      assertThat(reply.text()).contains("policy_metadata").doesNotContain("s3cr3t-value");
    }
  }

  // ---- PR #230 close-out 2: core's materialize, its sort and its non-lane skip ---------------------------------------------------

  private static final String FIVE_KEY_ACTOR =
      "{\"role\":\"implementer\",\"profile\":\"p\",\"tool\":\"claude\",\"model\":null,\"profile_source\":\"flag\"}";
  private static final String TIED_AT = "2026-11-07T10:00:00+00:00";

  @Test
  void aPidOfDigitsNewerThanUnicode14IsRefusedAndTheLogStaysWritable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      int sequence = 0;
      for (int codePoint : new int[] {0x11F51, 0x1E4F1, 0x116D1, 0x1E5F1, 0x10D41, 0x11BF1, 0x16131, 0x16D71, 0x1CCF1}) {
        String digit = new String(Character.toChars(codePoint));
        for (String pid : List.of(digit, "1" + digit, " " + digit + " ")) {
          String row = annotationRow(id(600 + sequence), "\"java-jenny\"", "{\"shell_pid\":\"" + pid + "\"}", "\"" + TIED_AT + "\"");
          assertRefusedAndLogWritable(rig, "annotation", WriteRig.annotation("WP01", row), null, sequence++);
        }
      }
      // R28: ASCII only, so Python's other int() spellings are refused too
      for (String pid : List.of("5_0", "\\u0665", "\\uff15", "\\u00a05")) {
        String row = annotationRow(id(600 + sequence), "\"java-jenny\"", "{\"shell_pid\":\"" + pid + "\"}", "\"" + TIED_AT + "\"");
        assertRefusedAndLogWritable(rig, "annotation", WriteRig.annotation("WP01", row), null, sequence++);
      }
      String ascii = annotationRow(id(700), "\"java-jenny\"", "{\"shell_pid\":\" +42 \",\"note\":\"n\"}", "\"" + TIED_AT + "\"");
      assertThat(rig.post("annotation", WriteRig.annotation("WP01", ascii)).status()).isEqualTo(201);
    }
  }

  @Test
  void theFiveKeyActorIsRefusedOnEveryRowKindAndTheLogStaysWritable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      assertRefusedAndLogWritable(rig, "unit", WriteRig.unit(withActor(WriteRig.claimRow(ID1), FIVE_KEY_ACTOR)), null, 0);
      String note = annotationRow(id(800), FIVE_KEY_ACTOR, "{\"note\":\"n\"}", "\"" + TIED_AT + "\"");
      assertRefusedAndLogWritable(rig, "annotation", WriteRig.annotation("WP01", note), null, 1);
      String second = WriteRig.unit("WP02", null, withActor(WriteRig.transitionRow(ID2, "WP02", "genesis", "planned", "java-jenny"), FIVE_KEY_ACTOR));
      byte[] before = Files.readAllBytes(rig.log());
      assertRefusal(rig.post("batch", WriteRig.batch(WriteRig.unit("WP01", null, WriteRig.claimRow(ID1)), second)), 400, "#/units/1");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void aFiveKeyActorAlreadyInTheLogIsReadAsCoresReaderReadsIt() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String legacy = WriteRig.priorPlannedLine().replace("\"actor\": \"java-jenny\"", "\"actor\": " + FIVE_KEY_ACTOR);
      Files.writeString(rig.log(), legacy);
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
    }
  }

  @Test
  void aSubtaskOfUninitializedIsRefusedAtTheEdgeAndAnExistingOneMakesTheLogUnreducible() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String row = annotationRow(id(900), "\"java-jenny\"", "{\"subtasks\":{\"T1\":\"uninitialized\"}}", "\"" + TIED_AT + "\"");
      assertRefusedAndLogWritable(rig, "annotation", WriteRig.annotation("WP01", row), null, 0);
      String poisoned = WriteRig.priorPlannedLine()
          + WriteRig.encodedRow(annotationRow(id(901), "\"java-jenny\"", "{\"subtasks\":{\"T1\":\"uninitialized\"}}", "\"" + TIED_AT + "\""));
      Files.writeString(rig.log(), poisoned);
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
      assertThat(reply.status()).as(reply.text()).isEqualTo(500);
      assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo("STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED");
      assertThat(Files.readString(rig.log())).isEqualTo(poisoned);
    }
  }

  @Test
  void aTransitionCoreWouldSkipAsNonLaneIsRefusedAtTheEdgeAndTheLogStaysWritable() throws Exception {
    List<String[]> markers = List.of(member("event_type", "\"DecisionPointOpened\""), member("event_type", "null"),
        member("event_name", "\"retrospective.requested\""), member("type", "\"RetrospectiveCaptured\""),
        member("type", "\"RetrospectiveCaptureFailed\""), member("type", "\"RetrospectiveSkipped\""));
    try (WriteRig rig = WriteRig.start()) {
      for (int i = 0; i < markers.size(); i++) {
        String row = withMember(WriteRig.claimRow(ID1), markers.get(i)[0], markers.get(i)[1]);
        assertRefusedAndLogWritable(rig, "unit", WriteRig.unit(row), null, i);
      }
      // a similar but different key is an ordinary field of a transition, as it is for core
      String other = withMember(WriteRig.claimRow(ID1), "event_name", "\"claim\"");
      assertThat(rig.post("unit", WriteRig.unit(other)).status()).isEqualTo(201);
    }
  }

  private static String planned(String eventIdJson, String at) {
    return "{\"actor\": \"java-jenny\", \"at\": \"" + at + "\", \"event_id\": " + eventIdJson
        + ", \"evidence\": null, \"execution_mode\": \"direct_repo\", \"force\": false, \"from_lane\": \"genesis\", "
        + "\"policy_metadata\": null, \"reason\": null, \"review_ref\": null, \"to_lane\": \"planned\", \"wp_id\": \"WP01\"}\n";
  }

  @Test
  void aPriorEventIdOfAnotherTypeThatTiesTheNewRowsAtIsRefusedAndOneThatTiesNothingIsRead() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      for (String prior : List.of("null", "5", "5.5", "true")) {
        // the new row's sort key would make core's sort compare a string with this id: the row is the one that cannot be read back
        String log = planned(prior, TIED_AT);
        Files.writeString(rig.log(), log);
        RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
        assertRefusal(reply, 400, null);
        assertThat(Files.readString(rig.log())).as(prior).isEqualTo(log);
        // an earlier `at` never compares the ids, so core reads the log and so does the service
        Files.writeString(rig.log(), planned(prior, "2026-11-07T09:00:00+00:00"));
        RawClient.Reply next = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
        assertThat(next.status()).as(prior + ": " + next.text()).isEqualTo(201);
      }
    }
  }

  @Test
  void aLogWhoseOwnEventIdsCannotBeOrderedRefusesEveryWriteWithAServerError() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String log = planned("null", TIED_AT) + planned("\"" + PRIOR_TIED_ID + "\"", TIED_AT);
      Files.writeString(rig.log(), log);
      RawClient.Reply reply = rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(id(950), "WP01", "n")));
      assertThat(reply.status()).as(reply.text()).isEqualTo(500);
      assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo("STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED");
      assertThat(Files.readString(rig.log())).isEqualTo(log);
    }
  }

  private static final String PRIOR_TIED_ID = "00000000000000000000000002";

  private static String[] member(String name, String json) {
    return new String[] {name, json};
  }

  /** The row with {@code name} set to {@code json}: replacing the claim row's own value, or appended when the row has none. */
  private static String withMember(String row, String name, String json) {
    String own = switch (name) {
      case "at" -> "\"at\":\"2026-11-07T10:00:00+00:00\"";
      case "actor" -> "\"actor\":\"java-jenny\"";
      case "policy_metadata" -> "\"policy_metadata\":null";
      default -> null;
    };
    String replacement = "\"" + name + "\":" + json;
    return own != null ? row.replace(own, replacement) : row.substring(0, row.length() - 1) + "," + replacement + "}";
  }

  private static String id(int n) {
    return "01J6XW9KQT7M0YB3N4R5CQZ2" + ALPHABET.charAt(n / 32 % 32) + ALPHABET.charAt(n % 32);
  }

  private static String annotationRow(String id, String actorJson, String deltaJson, String atJson) {
    return "{\"event_id\":\"" + id + "\",\"kind\":\"annotation\",\"wp_id\":\"WP01\",\"at\":" + atJson + ",\"actor\":" + actorJson
        + ",\"delta\":" + deltaJson + "}";
  }

  /** The request is a coded {@code REQUEST_MALFORMED}, nothing is written, and the next legal write to the Mission is acknowledged. */
  private static void assertRefusedAndLogWritable(WriteRig rig, String operation, String body, String instance, int sequence) throws Exception {
    byte[] before = Files.readAllBytes(rig.log());
    assertRefusal(rig.post(operation, body), 400, instance);
    assertThat(Files.readAllBytes(rig.log())).as(body).isEqualTo(before);
    RawClient.Reply next = rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(id(500 + sequence), "WP01", "after")));
    assertThat(next.status()).as("after " + body + ": " + next.text()).isEqualTo(201);
  }

  private static String claimFrom(String lane, String id) {
    return WriteRig.claimRow(id).replace("\"from_lane\":\"planned\"", "\"from_lane\":\"" + lane + "\"");
  }

  private static String withMetadata(String row, String json) {
    return row.replace("\"policy_metadata\":null", "\"policy_metadata\":" + json);
  }

  private static String withActor(String row, String actor) {
    return row.replace("\"actor\":\"java-jenny\"", "\"actor\":" + actor);
  }

  private static void assertRefusal(RawClient.Reply reply, int status, String instance) throws Exception {
    assertThat(reply.status()).as(reply.text()).isEqualTo(status);
    JsonNode body = JSON.readTree(reply.body());
    assertThat(body.get("code").asString()).isEqualTo("REQUEST_MALFORMED");
    assertThat(body.path("instance").asString(null)).isEqualTo(instance);
    assertThat(ContractSchemas.violations("WriteRefusal", reply.body())).isEmpty();
  }
}
