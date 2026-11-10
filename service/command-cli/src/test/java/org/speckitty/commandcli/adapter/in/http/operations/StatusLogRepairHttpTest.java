package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * User Story 1 of the write-service cut-over mission through the HTTP entry point: the status-log repair operation (#222), on a real
 * repository and the production application. Every body is validated against the contract's schemas, and every "nothing changed"
 * assertion sits next to a repair of the same fixture that does change it.
 */
class StatusLogRepairHttpTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID1 = "01J6XW9KQT7M0YB3N4R5CQZ2EY";
  private static final String ID2 = "01J6XW9KQT7M0YB3N4R5CQZ2EZ";
  private static final String ID3 = "01J6XW9KQT7M0YB3N4R5CQZ2F0";
  private static final String ID4 = "01J6XW9KQT7M0YB3N4R5CQZ2F1";
  private static final String PRIOR = WriteRig.priorPlannedLine();
  private static final String FRAGMENT = "{\"event_id\": \"01J6XW9KQT7M0YB3";
  private static final String QUARANTINE = "status.events.jsonl.torn";

  @Test
  void scenarioOneAMidJsonTailIsQuarantinedAndTheNextWriteSucceeds() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      rig.seedLog(PRIOR + FRAGMENT);

      RawClient.Reply reply = rig.repair();

      JsonNode ack = assertAck(reply, "quarantined", FRAGMENT.length(), QUARANTINE, true);
      assertThat(ack.get("repaired").asBoolean()).isTrue();
      assertThat(Files.readString(rig.log())).isEqualTo(PRIOR);
      assertThat(Files.readString(rig.quarantine())).isEqualTo(FRAGMENT + "\n");
      assertThat(reply.text()).doesNotContain(rig.repository.toString()).doesNotContain(rig.token.reveal());
      RawClient.Reply write = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));
      assertThat(write.status()).as(write.text()).isEqualTo(201);
      assertThat(Files.readString(rig.log())).isEqualTo(PRIOR + WriteRig.expectedClaimLine(ID1));
    }
  }

  @Test
  void scenarioTwoAWholeFoldableRowWithoutAnLfIsCompleted() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String line = WriteRig.expectedClaimLine(ID1);
      rig.seedLog(PRIOR + line.substring(0, line.length() - 1));

      assertAck(rig.repair(), "completed", 0, null, true);

      assertThat(Files.readString(rig.log())).isEqualTo(PRIOR + line);
      assertThat(Files.exists(rig.quarantine())).as("completing creates no quarantine file").isFalse();
    }
  }

  @Test
  void scenarioTwoBACompleteRowCollidingWithAnEarlierEventIdIsQuarantinedNotCompleted() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String colliding = WriteRig.expectedClaimLine(WriteRig.PRIOR_ID);
      String fragment = colliding.substring(0, colliding.length() - 1);
      rig.seedLog(PRIOR + fragment);

      assertAck(rig.repair(), "quarantined", fragment.length(), QUARANTINE, true);

      assertThat(Files.readString(rig.log())).isEqualTo(PRIOR);
      assertThat(Files.readString(rig.quarantine())).isEqualTo(fragment + "\n");
    }
  }

  @Test
  void scenarioThreeAHealthyEmptyMissingOrAlreadyRepairedLogIsNeverTouched() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      rig.seedLog(PRIOR + FRAGMENT);
      assertAck(rig.repair(), "quarantined", FRAGMENT.length(), QUARANTINE, true); // the positive control: this fixture does change

      List<String> states = List.of("already repaired", "healthy", "empty", "missing");
      for (String state : states) {
        switch (state) {
          case "healthy" -> rig.seedLog(PRIOR + WriteRig.expectedClaimLine(ID1));
          case "empty" -> rig.seedLog("");
          case "missing" -> Files.delete(rig.log());
          default -> { }
        }
        Path quarantine = rig.quarantine();
        boolean quarantineExisted = Files.exists(quarantine);
        List<String> before = stable(rig);
        byte[] logBefore = Files.exists(rig.log()) ? Files.readAllBytes(rig.log()) : null;

        JsonNode ack = assertAck(rig.repair(), "none", 0, null, true);

        assertThat(ack.get("repaired").asBoolean()).as(state).isFalse();
        assertThat(stable(rig)).as(state).isEqualTo(before);
        assertThat(Files.exists(rig.log())).as(state).isEqualTo(logBefore != null);
        if (logBefore != null) assertThat(Files.readAllBytes(rig.log())).as(state).isEqualTo(logBefore);
        assertThat(Files.exists(quarantine)).as(state + ": no file created").isEqualTo(quarantineExisted);
      }
    }
  }

  @Test
  void scenarioFourAWriteStillRefusesATornLogAndChangesNothing() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      rig.seedLog(PRIOR + FRAGMENT);
      byte[] before = Files.readAllBytes(rig.log());
      List<String> tree = stable(rig);

      RawClient.Reply write = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));

      assertRefusal(write, 500, "STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertThat(stable(rig)).isEqualTo(tree);
      assertThat(Files.exists(rig.quarantine())).isFalse();
      assertAck(rig.repair(), "quarantined", FRAGMENT.length(), QUARANTINE, true); // the control: the repair changes this very fixture
      assertThat(Files.readAllBytes(rig.log())).isNotEqualTo(before);
    }
  }

  @Test
  void scenarioFiveAHeldMissionLockIsRefusedAfterTheBudgetAndNothingChanges() throws Exception {
    try (WriteRig rig = WriteRig.start(Duration.ofMillis(800))) {
      rig.seedLog(PRIOR + FRAGMENT);
      byte[] before = Files.readAllBytes(rig.log());
      Path lock = rig.gitCommonDir.resolve("spec-kitty-locks/mission.status.lock");
      Files.createDirectories(lock.getParent());
      try (var ignored = LedgerLocks.forThisOs().acquire(lock, Duration.ofSeconds(2))) {
        long started = System.nanoTime();
        RawClient.Reply reply = rig.repair();
        long waitedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertRefusal(reply, 409, "STATUS_LOCK_HELD");
        assertThat(waitedMillis).as("the repair waited out the 0.8 s budget before refusing").isGreaterThanOrEqualTo(700);
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
        assertThat(Files.exists(rig.quarantine())).isFalse();
      }
      assertAck(rig.repair(), "quarantined", FRAGMENT.length(), QUARANTINE, true); // the control: the same request, once the lock is free
    }
  }

  @Test
  void theRunningServiceGivesWritesAndRepairsTheSameTenSecondBudget() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      Duration writeBudget = rig.bean(org.speckitty.commandcli.application.write.WriteService.class).budget();
      Duration repairBudget = rig.bean(org.speckitty.commandcli.application.repair.StatusLogRepairService.class).budget();

      assertThat(writeBudget).isEqualTo(Duration.ofSeconds(10));
      assertThat(repairBudget).isSameAs(writeBudget);
    }
  }

  @Test
  void scenarioSixADrainingServiceRefusesARepair() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      rig.seedLog(PRIOR + FRAGMENT);
      byte[] before = Files.readAllBytes(rig.log());
      rig.beginDrain();

      RawClient.Reply reply = rig.repair();

      assertRefusal(reply, 503, "SERVICE_DRAINING");
      assertThat(reply.head()).doesNotContainIgnoringCase("Retry-After");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertThat(Files.exists(rig.quarantine())).isFalse();
    }
  }

  @Test
  void scenarioSevenALocationTheWritePathCannotVerifyIsRefusedAndNothingChanges() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      rig.seedLog(PRIOR + FRAGMENT);
      byte[] before = Files.readAllBytes(rig.log());
      List<String> tree = stable(rig);
      String coordination = WriteRig.context().replace("checkout_append", "coordination_ref_commit")
          .replace("\"destinationRef\":null", "\"destinationRef\":\"kitty/mission-m\"");
      String elsewhere = WriteRig.context().replace(WriteRig.DIRECTORY, "kitty-specs/another");

      for (String context : List.of(coordination, elsewhere)) {
        RawClient.Reply reply = rig.postRepair(WriteRig.MISSION, WriteRig.repairRequest(context));

        assertRefusal(reply, 422, "WRITE_LOCATION_UNVERIFIED");
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
        assertThat(stable(rig)).isEqualTo(tree);
        assertThat(Files.exists(rig.quarantine())).isFalse();
      }
      assertAck(rig.repair(), "quarantined", FRAGMENT.length(), QUARANTINE, true); // the control: the verified location repairs
    }
  }

  @Test
  void scenarioNineAnUnknownOrMissingMemberIsRefusedAsMalformedAndNothingChanges() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      rig.seedLog(PRIOR + FRAGMENT);
      byte[] before = Files.readAllBytes(rig.log());
      String context = WriteRig.context();
      List<String> bodies = List.of(
          "{\"context\":" + context + ",\"extra\":1}",
          "{}",
          "",
          "[]",
          "{\"context\":" + context + "} {}",
          "{\"context\":" + context + ",\"context\":" + context + "}",
          "{\"context\":" + context.replace("\"destinationRef\":null", "\"destinationRef\":7") + "}",
          "{\"context\":" + context.replace("checkout_append", "elsewhere") + "}",
          "{\"context\":" + context.replace("\"actor\":\"java-jenny\"", "\"actor\":\"\"") + "}",
          "{\"context\":" + context.replace(WriteRig.DIRECTORY, "../outside") + "}");

      for (String body : bodies) {
        RawClient.Reply reply = rig.postRepair(WriteRig.MISSION, body);

        assertRefusal(reply, 400, "REQUEST_MALFORMED");
        assertThat(reply.text()).doesNotContain("extra").doesNotContain("outside");
        assertThat(Files.readAllBytes(rig.log())).as(body).isEqualTo(before);
      }
      assertThat(rig.postRepair("not-a-ulid", WriteRig.repairRequest(context)).status()).isEqualTo(400);
      assertAck(rig.repair(), "quarantined", FRAGMENT.length(), QUARANTINE, true); // the control: the well-formed request repairs
    }
  }

  @Test
  void scenarioTenWritesAndRepairsInterleavedNeverMixAndTheFragmentIsKeptOnce() throws Exception {
    int writes = 24;
    int repairs = 4;
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try (WriteRig rig = WriteRig.start()) {
      rig.seedLog(PRIOR + FRAGMENT);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<RawClient.Reply>> writeReplies = new ArrayList<>();
      List<Future<RawClient.Reply>> repairReplies = new ArrayList<>();
      for (int index = 0; index < writes; index++) {
        String body = WriteRig.annotation("WP01", WriteRig.annotationRow(id(index), "WP01", "note" + index));
        writeReplies.add(executor.submit(() -> {
          start.await();
          return rig.post("annotation", body);
        }));
        if (index % (writes / repairs) == 0) {
          repairReplies.add(executor.submit(() -> {
            start.await();
            return rig.repair();
          }));
        }
      }
      start.countDown();

      int quarantined = 0;
      for (Future<RawClient.Reply> future : repairReplies) {
        RawClient.Reply reply = future.get(60, TimeUnit.SECONDS);
        assertThat(reply.status()).as(reply.text()).isEqualTo(200);
        assertThat(ContractSchemas.violations("StatusLogRepairAck", reply.body())).isEmpty();
        String action = JSON.readTree(reply.body()).get("action").asString();
        assertThat(action).isIn("quarantined", "none");
        if (action.equals("quarantined")) quarantined++;
      }
      assertThat(quarantined).as("exactly one repair finds the tear").isEqualTo(1);
      List<String> expectedLines = new ArrayList<>();
      for (int index = 0; index < writes; index++) {
        RawClient.Reply reply = writeReplies.get(index).get(60, TimeUnit.SECONDS);
        assertThat(reply.status()).as(reply.text()).isIn(201, 500);
        if (reply.status() == 500) {
          assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo("STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED");
        } else {
          expectedLines.add(WriteRig.encodedRow(WriteRig.annotationRow(id(index), "WP01", "note" + index)));
        }
      }

      assertThat(expectedLines).as("the positive control: some writes landed after the repair").isNotEmpty();
      String log = Files.readString(rig.log());
      assertThat(log).startsWith(PRIOR).endsWith("\n");
      List<String> lines = new ArrayList<>(List.of(log.substring(PRIOR.length()).split("(?<=\n)")));
      lines.removeIf(String::isEmpty);
      assertThat(lines).as("prefix plus whole appended units, none torn, none lost").containsExactlyInAnyOrderElementsOf(expectedLines);
      assertThat(Files.readString(rig.quarantine())).isEqualTo(FRAGMENT + "\n");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void scenarioElevenARepairAndAWriteWaitForTheSameTurnAndTimeOutAsBusy() throws Exception {
    try (WriteRig rig = WriteRig.start(Duration.ofMillis(800))) {
      rig.seedLog(PRIOR + FRAGMENT);
      byte[] before = Files.readAllBytes(rig.log());
      try (var ignored = rig.turn().tryAcquire(0).orElseThrow()) {
        long started = System.nanoTime();
        RawClient.Reply repair = rig.repair();
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis())
            .as("the repair waited on the shared turn for the 0.8 s budget").isGreaterThanOrEqualTo(700);
        RawClient.Reply write = rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID1)));

        assertRefusal(repair, 503, "SERVICE_BUSY");
        assertThat(repair.head()).containsIgnoringCase("Retry-After: 1");
        assertRefusal(write, 503, "SERVICE_BUSY");
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
        assertThat(Files.exists(rig.quarantine())).isFalse();
      }
      assertAck(rig.repair(), "quarantined", FRAGMENT.length(), QUARANTINE, true); // the control: free turn, same request
    }
  }

  @Test
  void scenarioTwelveATornTwoRowUnitKeepsRowOneAndARetryOfTheUnitIsAConflict() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String rowTwo = WriteRig.annotationRow(ID2, "WP01", "second");
      String unit = WriteRig.unit("WP01", null, WriteRig.claimRow(ID1) + "," + rowTwo);
      String encodedTwo = WriteRig.encodedRow(rowTwo);
      String torn = encodedTwo.substring(0, encodedTwo.length() / 2);
      rig.seedLog(PRIOR + WriteRig.expectedClaimLine(ID1) + torn);

      assertAck(rig.repair(), "quarantined", torn.length(), QUARANTINE, true);

      assertThat(Files.readString(rig.log())).isEqualTo(PRIOR + WriteRig.expectedClaimLine(ID1));
      assertRefusal(rig.post("unit", unit), 409, "EVENT_ID_CONFLICT");
      assertThat(Files.readString(rig.log())).as("the retry changed nothing").isEqualTo(PRIOR + WriteRig.expectedClaimLine(ID1));
      String reissued = WriteRig.unit("WP01", null, WriteRig.transitionRow(ID3, "WP01", "claimed", "in_progress", "java-jenny") + ","
          + WriteRig.annotationRow(ID4, "WP01", "second"));
      RawClient.Reply fresh = rig.post("unit", reissued);
      assertThat(fresh.status()).as(fresh.text()).isEqualTo(201);
    }
  }

  @Test
  void scenarioThirteenAnUnreadableLogAfterTheRepairIsReportedNotRefused() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String unreducible = "{\"event_id\": \"00000000000000000000000002\", \"kind\": \"unknown\"}\n";
      rig.seedLog(PRIOR + unreducible + FRAGMENT);

      JsonNode ack = assertAck(rig.repair(), "quarantined", FRAGMENT.length(), QUARANTINE, false);

      assertThat(ack.get("readableAfterRepair").asBoolean()).isFalse();
      assertThat(Files.readString(rig.log())).isEqualTo(PRIOR + unreducible);
      assertThat(Files.readString(rig.quarantine())).isEqualTo(FRAGMENT + "\n");

      String line = WriteRig.expectedClaimLine(ID1);
      Files.delete(rig.quarantine());
      rig.seedLog(PRIOR + unreducible + line.substring(0, line.length() - 1));
      assertAck(rig.repair(), "quarantined", line.length() - 1, QUARANTINE, false);
      assertThat(Files.readString(rig.log())).as("a whole row on an unreducible prefix is quarantined").isEqualTo(PRIOR + unreducible);

      rig.seedLog(PRIOR + unreducible);
      assertAck(rig.repair(), "none", 0, null, false);
    }
  }

  @Test
  void aRepairTheStoreRefusesIsACodedPersistenceFailureThatKeepsEveryByte() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      rig.seedLog(PRIOR + FRAGMENT);
      Files.createDirectory(rig.quarantine());
      byte[] before = Files.readAllBytes(rig.log());

      RawClient.Reply reply = rig.repair();

      assertRefusal(reply, 500, "STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertThat(Files.isDirectory(rig.quarantine())).isTrue();
      Files.delete(rig.quarantine());
      assertAck(rig.repair(), "quarantined", FRAGMENT.length(), QUARANTINE, true); // the control: without the obstacle it repairs
    }
  }

  private static String id(int index) {
    return "0000000000000000000000" + String.format("%04d", index + 100);
  }

  /** The Mission directory exactly, and the git directory without the lock files the running service holds. */
  private static List<String> stable(WriteRig rig) {
    List<String> all = new ArrayList<>(rig.tree().stream()
        .filter(entry -> {
          String unix = entry.replace('\\', '/'); // Windows prints the relative path with backslashes
          return !unix.startsWith("spec-kitty-locks/") && !unix.startsWith("spec-kitty-write-service/");
        }).toList());
    all.addAll(rig.missionTree());
    return all;
  }

  private static JsonNode assertAck(RawClient.Reply reply, String action, long moved, String file, boolean readable) {
    assertThat(reply.status()).as(reply.text()).isEqualTo(200);
    assertThat(reply.head()).containsIgnoringCase("Content-Type: application/json");
    assertThat(ContractSchemas.violations("StatusLogRepairAck", reply.body())).isEmpty();
    JsonNode ack = JSON.readTree(reply.body());
    assertThat(ack.get("action").asString()).isEqualTo(action);
    assertThat(ack.get("repaired").asBoolean()).isEqualTo(!action.equals("none"));
    assertThat(ack.get("movedBytes").asLong()).isEqualTo(moved);
    if (file == null) assertThat(ack.get("quarantinedFile").isNull()).isTrue();
    else assertThat(ack.get("quarantinedFile").asString()).isEqualTo(file);
    assertThat(ack.get("readableAfterRepair").asBoolean()).isEqualTo(readable);
    assertThat(reply.body()).startsWith("{\"repaired\":");
    assertThat(List.copyOf(ack.propertyNames())).containsExactly("repaired", "action", "movedBytes", "quarantinedFile", "readableAfterRepair");
    return ack;
  }

  private static void assertRefusal(RawClient.Reply reply, int status, String code) {
    assertThat(reply.status()).as(reply.text()).isEqualTo(status);
    assertThat(ContractSchemas.violations("WriteRefusal", reply.body())).isEmpty();
    assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo(code);
    assertThat(reply.text()).doesNotContain("/tmp").doesNotContain("command-cli-write-repository");
  }

}
