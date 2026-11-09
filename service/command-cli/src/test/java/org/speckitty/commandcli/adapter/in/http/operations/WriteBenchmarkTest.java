package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.json.JsonMapper;

/** NFR-001: fifty real acknowledged claims against a Mission log holding exactly 10,000 rows. */
@Tag("benchmark")
class WriteBenchmarkTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int INITIAL_ROWS = 10_000;
  private static final int UNITS = 50;

  @Test
  void acknowledgedUnitLatencyOnATenThousandRowLog() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      seedLog(rig);
      List<Long> nanos = new ArrayList<>(UNITS);
      for (int index = 0; index < UNITS; index++) {
        String wp = wp(index);
        String row = WriteRig.transitionRow(eventId(INITIAL_ROWS + index), wp, "planned", "claimed", "java-jenny");
        long started = System.nanoTime();
        RawClient.Reply reply = rig.post("unit", WriteRig.unit(wp, null, row));
        nanos.add(System.nanoTime() - started);
        assertThat(reply.status()).as(reply.text()).isEqualTo(201);
        assertThat(JSON.readTree(reply.body()).required("appendedLines").asInt()).isOne();
      }
      nanos.sort(Comparator.naturalOrder());
      double p50 = millis(percentile(nanos, 0.50));
      double p95 = millis(percentile(nanos, 0.95));
      Path report = Path.of(System.getProperty("commandCli.benchmarkReport", "build/benchmark/write-latency.json"));
      Files.createDirectories(report.getParent());
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("logRows", INITIAL_ROWS);
      values.put("acknowledgedUnits", UNITS);
      values.put("p50Ms", p50);
      values.put("p95Ms", p95);
      JSON.writeValue(report.toFile(), values);
      System.out.printf("Write benchmark: 10,000-row log; 50 acknowledged units; p50 %.3f ms; p95 %.3f ms%n", p50, p95);
      assertThat(p95).as("NFR-001 p95 (report: %s)", report).isLessThanOrEqualTo(Duration.ofSeconds(1).toMillis());
    }
  }

  private static void seedLog(WriteRig rig) throws Exception {
    StringBuilder log = new StringBuilder(INITIAL_ROWS * 300);
    for (int index = 0; index < UNITS; index++) {
      log.append(WriteRig.encodedRow(WriteRig.transitionRow(eventId(index), wp(index), "genesis", "planned", "java-jenny")));
      if (index > 1) {
        Files.writeString(rig.missionDirectory.resolve("tasks/" + wp(index) + ".md"),
            "---\nwork_package_id: " + wp(index) + "\ndependencies: []\nsubtasks: []\n---\n");
      }
    }
    for (int index = UNITS; index < INITIAL_ROWS; index++) {
      log.append(WriteRig.encodedRow(WriteRig.annotationRow(eventId(index), "WP01", "history")));
    }
    Files.writeString(rig.log(), log.toString());
    assertThat(Files.readString(rig.log()).lines().count()).isEqualTo(INITIAL_ROWS);
  }

  private static String wp(int index) {
    return String.format("WP%02d", index + 1);
  }

  private static String eventId(int value) {
    return String.format("%026d", value);
  }

  private static long percentile(List<Long> sorted, double quantile) {
    return sorted.get((int) Math.ceil(sorted.size() * quantile) - 1);
  }

  private static double millis(long nanos) {
    return nanos / 1_000_000.0;
  }
}
