package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** US1-US5 acceptance scenarios and first/empty-log edges over real HTTP with byte checks. */
class WriteIntegrationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID1 = "01J6XW9KQT7M0YB3N4R5CQZ2EY";
  private static final String ID2 = "01J6XW9KQT7M0YB3N4R5CQZ2EZ";
  private static final String ID3 = "01J6XW9KQT7M0YB3N4R5CQZ2F0";
  private static final String ID4 = "01J6XW9KQT7M0YB3N4R5CQZ2F1";

  @Test
  void us1ScenarioOneALegalClaimIsDurablyWrittenByteExact() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      JsonNode ack = JSON.readTree(reply.body());
      assertThat(ack.get("appendedLines").asInt()).isEqualTo(1);
      assertThat(ack.get("eventIds").get(0).asString()).isEqualTo(ID1);
      assertThat(ack.get("fsynced").asBoolean()).isTrue();
      assertThat(ack.get("replayed").asBoolean()).isFalse();
      assertThat(ack.has("statusCommit") && ack.get("statusCommit").isNull()).isTrue();
      assertThat(Files.readString(rig.log())).isEqualTo(WriteRig.priorPlannedLine() + WriteRig.expectedClaimLine(ID1));
      assertThat(ContractSchemas.violations("DurabilityAck", reply.body())).isEmpty();
    }
  }

  @Test
  void us1ScenarioTwoAnIdenticalRetryReplaysWithoutChangingBytes() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String body = WriteRig.unit(WriteRig.claimRow(ID1));
      assertThat(rig.post("unit", body).status()).isEqualTo(201);
      byte[] before = Files.readAllBytes(rig.log());
      RawClient.Reply replay = rig.post("unit", body);
      assertThat(replay.status()).isEqualTo(201);
      JsonNode ack = JSON.readTree(replay.body());
      assertThat(ack.get("replayed").asBoolean()).isTrue();
      assertThat(ack.get("appendedLines").asInt()).isZero();
      assertThat(ack.get("fsynced").asBoolean()).isTrue();
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void us1ScenarioThreeChangedAndPartiallyLoggedUnitsConflict() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String row = WriteRig.claimRow(ID1);
      assertThat(rig.post("unit", WriteRig.unit(row)).status()).isEqualTo(201);
      byte[] before = Files.readAllBytes(rig.log());
      String changed = row.replace("\"reason\":null", "\"reason\":\"changed\"");
      RawClient.Reply changedReply = rig.post("unit", WriteRig.unit(changed));
      assertRefusal(changedReply, 409, "EVENT_ID_CONFLICT", null);
      String partialUnit = "{\"wpId\":\"WP01\",\"rows\":[" + row + "," + WriteRig.annotationRow(ID2, "WP01", "new")
          + "],\"context\":" + WriteRig.context() + "}";
      assertRefusal(rig.post("unit", partialUnit), 409, "EVENT_ID_CONFLICT", null);
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void us1ScenarioFourARepeatedRequestIdIsRejected() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String unit = "{\"wpId\":\"WP01\",\"rows\":[" + WriteRig.claimRow(ID1) + ","
          + WriteRig.annotationRow(ID1, "WP01", "duplicate") + "],\"context\":" + WriteRig.context() + "}";
      byte[] before = Files.readAllBytes(rig.log());
      assertRefusal(rig.post("unit", unit), 409, "BOOKKEEPING_DOUBLE_EVENT_ID", null);
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void us1ScenarioFiveAWriteWaitsForThePeerMissionLock() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      Path lock = rig.gitCommonDir.resolve("spec-kitty-locks/mission.status.lock");
      Files.createDirectories(lock.getParent());
      CountDownLatch held = new CountDownLatch(1);
      Thread peer = Thread.ofPlatform().start(() -> {
        try (var ignored = LedgerLocks.forThisOs().acquire(lock, Duration.ofSeconds(2))) {
          held.countDown();
          Thread.sleep(200);
        } catch (Exception exception) {
          throw new RuntimeException(exception);
        }
      });
      assertThat(held.await(1, TimeUnit.SECONDS)).isTrue();
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
      peer.join();
      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
    }
  }

  @Test
  void us1ScenarioSixATornTailIsRefusedUnchanged() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      Files.writeString(rig.log(), WriteRig.priorPlannedLine() + "{\"event_id\":");
      byte[] before = Files.readAllBytes(rig.log());
      assertRefusal(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1))), 500,
          "STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED", null);
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void us2ScenarioOneAnIllegalTransitionCarriesItsReasonUrn() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.transitionRow(ID1, "WP01", "planned", "done", "java-jenny")));
      assertRefusal(reply, 409, "TRANSITION_REJECTED", "urn:spec-kitty:write-refusal:illegal_transition");
      assertThat(Files.readString(rig.log())).isEqualTo(WriteRig.priorPlannedLine());
    }
  }

  @Test
  void us2ScenarioTwoUnsatisfiedDependenciesAreRefused() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      Files.writeString(rig.missionDirectory.resolve("tasks/WP02-write.md"),
          "---\nwork_package_id: WP02\ndependencies: [WP01]\nsubtasks: []\n---\n");
      Files.writeString(rig.log(), WriteRig.encodedRow(WriteRig.transitionRow(ID4, "WP02", "genesis", "planned", "java-jenny")),
          StandardOpenOptionsAppend());
      RawClient.Reply reply = rig.post("unit", WriteRig.unit("WP02", null,
          WriteRig.transitionRow(ID1, "WP02", "planned", "claimed", "java-jenny")));
      assertRefusal(reply, 409, "DEPENDENCIES_NOT_SATISFIED", "urn:spec-kitty:write-refusal:dependencies_unsatisfied");
    }
  }

  @Test
  void us2ScenarioThreeAnotherActorsImplementationClaimConflicts() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      Files.writeString(rig.log(), WriteRig.encodedRow(WriteRig.transitionRow(ID4, "WP01", "planned", "claimed", "other")),
          StandardOpenOptionsAppend());
      RawClient.Reply reply = rig.post("unit", WriteRig.unit("WP01", "start_implementation",
          WriteRig.transitionRow(ID1, "WP01", "claimed", "in_progress", "java-jenny")));
      assertRefusal(reply, 409, "WP_ALREADY_CLAIMED", "urn:spec-kitty:write-refusal:claim_held_by_other_actor");
    }
  }

  @Test
  void us2ScenarioFiveUnknownMissionAndWorkPackageAre404() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      RawClient.Reply mission = rig.postMission("00000000000000000000000000", "unit", WriteRig.unit(WriteRig.claimRow(ID1)));
      assertRefusal(mission, 404, "MISSION_NOT_FOUND", null);
      RawClient.Reply wp = rig.post("unit", WriteRig.unit("WP99", null,
          WriteRig.transitionRow(ID1, "WP99", "planned", "claimed", "java-jenny")));
      assertRefusal(wp, 404, "WP_NOT_FOUND", null);
    }
  }

  @Test
  void us3ScenarioOneASequentialBatchHasOneFsyncAndItsReplayHasZero() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String first = WriteRig.unit(WriteRig.claimRow(ID1));
      String second = WriteRig.unit(WriteRig.transitionRow(ID2, "WP01", "claimed", "in_progress", "java-jenny"));
      String batch = WriteRig.batch(first, second);
      RawClient.Reply reply = rig.post("batch", batch);
      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      JsonNode ack = JSON.readTree(reply.body());
      assertThat(ack.get("fsyncs").asInt()).isOne();
      assertThat(ack.get("units")).hasSize(2);
      assertThat(ContractSchemas.violations("BatchDurabilityAck", reply.body())).isEmpty();
      assertThat(Files.readString(rig.log())).endsWith(WriteRig.expectedClaimLine(ID1)
          + WriteRig.encodedRow(WriteRig.transitionRow(ID2, "WP01", "claimed", "in_progress", "java-jenny")));
      byte[] before = Files.readAllBytes(rig.log());
      RawClient.Reply replay = rig.post("batch", batch);
      assertThat(JSON.readTree(replay.body()).get("fsyncs").asInt()).isZero();
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void us3ScenarioTwoAnIllegalFourthUnitNamesUnitsThreeAndWritesNothing() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String batch = WriteRig.batch(
          WriteRig.unit(WriteRig.claimRow(ID1)),
          WriteRig.unit(WriteRig.transitionRow(ID2, "WP01", "claimed", "in_progress", "java-jenny")),
          WriteRig.unit(WriteRig.transitionRow(ID3, "WP01", "in_progress", "blocked", "java-jenny")),
          WriteRig.unit(WriteRig.transitionRow(ID4, "WP01", "blocked", "done", "java-jenny")));
      byte[] before = Files.readAllBytes(rig.log());
      RawClient.Reply reply = rig.post("batch", batch);
      assertRefusal(reply, 409, "TRANSITION_REJECTED", "urn:spec-kitty:write-refusal:illegal_transition");
      assertThat(JSON.readTree(reply.body()).get("instance").asString()).isEqualTo("#/units/3");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void us3ScenarioThreeMixedLocationsAreMalformed() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String other = WriteRig.unit(WriteRig.claimRow(ID2)).replace("kitty-specs/mission", "kitty-specs/other");
      RawClient.Reply reply = rig.post("batch", WriteRig.batch(WriteRig.unit(WriteRig.claimRow(ID1)), other));
      assertRefusal(reply, 400, "REQUEST_MALFORMED", null);
      assertThat(JSON.readTree(reply.body()).get("instance").asString()).isEqualTo("#/units/1");
    }
  }

  @Test
  void batchLimit64IsAcceptedAnd65IsMalformed() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      List<String> units = new ArrayList<>();
      StringBuilder prior = new StringBuilder(WriteRig.priorPlannedLine());
      for (int index = 0; index < 65; index++) {
        String wp = String.format("WP%02d", index + 1);
        String id = String.format("%026d", index + 100);
        if (index != 1) {
          Files.writeString(rig.missionDirectory.resolve("tasks/" + wp + ".md"),
              "---\nwork_package_id: " + wp + "\ndependencies: []\nsubtasks: []\n---\n");
        }
        String previousId = String.format("%026d", index + 1000);
        if (index >= 1) prior.append(WriteRig.encodedRow(WriteRig.transitionRow(previousId, wp, "genesis", "planned", "java-jenny")));
        units.add(WriteRig.unit(wp, null, WriteRig.transitionRow(id, wp, "planned", "claimed", "java-jenny")));
      }
      Files.writeString(rig.log(), prior.toString());
      RawClient.Reply accepted = rig.post("batch", WriteRig.batch(units.subList(0, 64).toArray(String[]::new)));
      assertThat(accepted.status()).as(accepted.text()).isEqualTo(201);
      assertThat(JSON.readTree(accepted.body()).get("units")).hasSize(64);
      assertRefusal(rig.post("batch", WriteRig.batch(units.toArray(String[]::new))), 400, "REQUEST_MALFORMED", null);
    }
  }

  @Test
  void us4RootCheckoutProceedsButMismatchesAreUnverified() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String body = WriteRig.unit(WriteRig.claimRow(ID1));
      for (String mismatch : List.of(body.replace("\"checkout\":\".\"", "\"checkout\":\"lane\""),
          body.replace("kitty-specs/mission", "kitty-specs/other"),
          body.replace("\"destinationRef\":null", "\"destinationRef\":\"refs/heads/other\""))) {
        byte[] before = Files.readAllBytes(rig.log());
        assertRefusal(rig.post("unit", mismatch), 422, "WRITE_LOCATION_UNVERIFIED", null);
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      }
      assertThat(rig.post("unit", body).status()).isEqualTo(201);
    }
  }

  @Test
  void us4CoordinationRefCommitChangesNoRepositoryBytes() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String body = WriteRig.unit(WriteRig.claimRow(ID1)).replace("checkout_append", "coordination_ref_commit")
          .replace("\"destinationRef\":null", "\"destinationRef\":\"refs/heads/mission\"");
      String before = treeHash(rig.repository);
      RawClient.Reply reply = rig.post("unit", body);
      assertRefusal(reply, 422, "WRITE_LOCATION_UNVERIFIED", null);
      assertThat(JSON.readTree(reply.body()).get("detail").asString()).contains("migration step 4");
      assertThat(treeHash(rig.repository)).isEqualTo(before);
    }
  }

  @Test
  void us4CoordinationRoutedMissionRefusesCheckoutAppendBeforeAnyLock() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      Files.writeString(rig.missionDirectory.resolve("meta.json"),
          "{\"mission_id\":\"" + WriteRig.MISSION + "\",\"coordination_branch\":\"kitty/mission-status\"}\n");
      String before = treeHash(rig.repository);
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
      assertRefusal(reply, 422, "WRITE_LOCATION_UNVERIFIED", null);
      assertThat(treeHash(rig.repository)).isEqualTo(before);
    }
  }

  @Test
  void us5AnnotationsAreByteExactReplayAndUnknownWpIs404() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String body = WriteRig.annotation("WP01", WriteRig.annotationRow(ID1, "WP01", "note"));
      RawClient.Reply reply = rig.post("annotation", body);
      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      assertThat(JSON.readTree(reply.body()).get("appendedLines").asInt()).isOne();
      assertThat(Files.readString(rig.log())).endsWith(WriteRig.encodedRow(WriteRig.annotationRow(ID1, "WP01", "note")));
      byte[] before = Files.readAllBytes(rig.log());
      assertThat(JSON.readTree(rig.post("annotation", body).body()).get("replayed").asBoolean()).isTrue();
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertRefusal(rig.post("annotation", WriteRig.annotation("WP99", WriteRig.annotationRow(ID2, "WP99", "note"))),
          404, "WP_NOT_FOUND", null);
    }
  }

  @Test
  void escapingSessionTimesNumbersAndNestedIdentityFieldsAreByteExact() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String extras = ",\"text\":\"é—😀\\n\\u0001\",\"session_started_at\":\"2026-01-01T00:00:00.250000+00:00\","
          + "\"session_ended_at\":\"2026-01-01T00:00:02.750000+00:00\",\"machine_name\":\"top\","
          + "\"hostname\":\"top\",\"workspace_path\":\"/secret\",\"developer_name\":\"top\","
          + "\"developer_email\":\"top@example.invalid\",\"nested\":{\"hostname\":\"nested\",\"keep\":1},"
          + "\"objects\":[{\"workspace_path\":\"/secret\",\"keep\":2}],\"number\":1.0,\"integer\":9007199254740993,"
          + "\"nothing\":null,\"enabled\":true}";
      String row = WriteRig.claimRow(ID1).substring(0, WriteRig.claimRow(ID1).length() - 1) + extras;

      RawClient.Reply reply = rig.post("unit", WriteRig.unit(row));

      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      String encoded = WriteRig.encodedRow(row);
      assertThat(Files.readString(rig.log())).isEqualTo(WriteRig.priorPlannedLine() + encoded);
      assertThat(encoded).contains("\"session_duration_s\": 2").contains("\\ud83d\\ude00")
          .doesNotContain("/secret").doesNotContain("developer_email").doesNotContain("session_started_at");
    }
  }

  @Test
  void aCrLfLogReplaysWithoutChangingItsBytes() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String body = WriteRig.unit(WriteRig.claimRow(ID1));
      String crlf = (WriteRig.priorPlannedLine() + WriteRig.expectedClaimLine(ID1)).replace("\n", "\r\n");
      Files.writeString(rig.log(), crlf);
      byte[] before = Files.readAllBytes(rig.log());

      RawClient.Reply reply = rig.post("unit", body);

      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      assertThat(JSON.readTree(reply.body()).get("replayed").asBoolean()).isTrue();
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void interiorLogLinesFollowTheFoldAndUnreducibleLinesStayUntouched() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String skippable = WriteRig.priorPlannedLine() + "\n"
          + "{\"event_id\":\"00000000000000000000000002\",\"event_type\":\"non_lane\"}\n";
      Files.writeString(rig.log(), skippable);
      RawClient.Reply accepted = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
      assertThat(accepted.status()).as(accepted.text()).isEqualTo(201);
      assertThat(Files.readString(rig.log())).isEqualTo(skippable + WriteRig.expectedClaimLine(ID1));

      for (String malformed : List.of("{bad\n", "{\"event_id\":\"00000000000000000000000002\",\"kind\":\"unknown\"}\n")) {
        Files.writeString(rig.log(), WriteRig.priorPlannedLine() + malformed);
        byte[] before = Files.readAllBytes(rig.log());
        assertRefusal(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1))), 500,
            "STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED", null);
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      }
    }
  }

  @Test
  void aWriteAlreadyInFlightFinishesWhileLaterWritesSeeServiceDraining() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (WriteRig rig = WriteRig.start()) {
      Path lock = rig.gitCommonDir.resolve("spec-kitty-locks/mission.status.lock");
      Files.createDirectories(lock.getParent());
      var peer = LedgerLocks.forThisOs().acquire(lock, Duration.ofSeconds(2));
      try {
        Future<RawClient.Reply> started = executor.submit(() -> rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1))));
        awaitInFlight(rig, 1);
        Thread.sleep(100); // let the started request pass the drain check and reach the held Mission lock
        rig.beginDrain();
        RawClient.Reply later = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID2)));
        assertRefusal(later, 503, "SERVICE_DRAINING", null);
        peer.close();
        assertThat(started.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
        assertThat(Files.readString(rig.log())).isEqualTo(WriteRig.priorPlannedLine() + WriteRig.expectedClaimLine(ID1));
      } finally {
        peer.close();
        executor.shutdownNow();
      }
    }
  }

  @Test
  void concurrentHttpWritesAreSerializedAndTheSecondSeesTheFirstResult() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (WriteRig rig = WriteRig.start()) {
      Path lock = rig.gitCommonDir.resolve("spec-kitty-locks/mission.status.lock");
      Files.createDirectories(lock.getParent());
      var peer = LedgerLocks.forThisOs().acquire(lock, Duration.ofSeconds(2));
      try {
        Future<RawClient.Reply> first = executor.submit(() -> rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1))));
        awaitInFlight(rig, 1);
        Thread.sleep(100); // let the first request acquire the service turn before the second joins the fair queue
        String secondRow = WriteRig.transitionRow(ID2, "WP01", "claimed", "in_progress", "java-jenny");
        Future<RawClient.Reply> second = executor.submit(() -> rig.post("unit", WriteRig.unit(secondRow)));
        awaitInFlight(rig, 2);
        peer.close();

        assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
        assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
        assertThat(Files.readString(rig.log())).isEqualTo(WriteRig.priorPlannedLine()
            + WriteRig.expectedClaimLine(ID1) + WriteRig.encodedRow(secondRow));
      } finally {
        peer.close();
        executor.shutdownNow();
      }
    }
  }

  @Test
  void firstAndEmptyLogsAcceptAGenesisTransition() throws Exception {
    for (boolean empty : new boolean[] {false, true}) {
      try (WriteRig rig = WriteRig.start()) {
        if (empty) Files.writeString(rig.log(), "");
        else Files.delete(rig.log());
        String row = WriteRig.transitionRow(ID1, "WP01", "genesis", "planned", "java-jenny");
        RawClient.Reply reply = rig.post("unit", WriteRig.unit(row));
        assertThat(reply.status()).as(reply.text()).isEqualTo(201);
        assertThat(Files.readString(rig.log())).isEqualTo(WriteRig.encodedRow(row));
      }
    }
  }

  private static java.nio.file.StandardOpenOption StandardOpenOptionsAppend() {
    return java.nio.file.StandardOpenOption.APPEND;
  }

  private static void awaitInFlight(WriteRig rig, int count) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (rig.inFlight() < count && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(rig.inFlight()).isGreaterThanOrEqualTo(count);
  }

  private static void assertRefusal(RawClient.Reply reply, int status, String code, String type) throws Exception {
    assertThat(reply.status()).as(reply.text()).isEqualTo(status);
    JsonNode body = JSON.readTree(reply.body());
    assertThat(body.get("code").asString()).isEqualTo(code);
    assertThat(body.path("type").asString(null)).isEqualTo(type);
    assertThat(ContractSchemas.violations("WriteRefusal", reply.body())).isEmpty();
  }

  private static String treeHash(Path repository) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (Stream<Path> paths = Files.walk(repository)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        digest.update(repository.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
        digest.update(Files.readAllBytes(path));
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
