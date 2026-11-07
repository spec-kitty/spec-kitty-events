package org.speckitty.missionstatus.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;

class StatusLogFaultInjectionTest {

  private static final int ITERATIONS = 120;

  @TempDir Path repository;

  @Test
  void rollbackAndDurabilityHoldAcrossInjectedFaults() throws Exception {
    Path gcd = Files.createDirectories(repository.resolve(".git"));
    Path mission = Files.createDirectories(repository.resolve("kitty-specs/mission"));
    Path log = mission.resolve("status.events.jsonl");
    Map<Fault, Integer> counts = new EnumMap<>(Fault.class);
    for (Fault fault : Fault.values()) {
      counts.put(fault, 0);
    }

    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      Fault fault = Fault.values()[iteration % Fault.values().length];
      counts.merge(fault, 1, Integer::sum);
      if (iteration % 10 == 0) {
        Files.deleteIfExists(log);
      }
      byte[] before = Files.exists(log) ? Files.readAllBytes(log) : new byte[0];
      byte[] line = ("{\"iteration\": " + iteration + "}\n").getBytes(StandardCharsets.UTF_8);
      FaultyFileOps files = new FaultyFileOps(fault);
      StatusLogAppender appender = new StatusLogAppender(LedgerLocks.forThisOs(), files);
      AtomicBoolean appendReturned = new AtomicBoolean();

      if (fault == Fault.KILL_AFTER_SYNC) {
        assertThatThrownBy(
                () ->
                    appender.withMissionLock(
                        gcd,
                        mission,
                        System.nanoTime() + 5_000_000_000L,
                        session -> {
                          session.appendAndSync(line);
                          appendReturned.set(true);
                          throw new IOException("simulated crash before the answer");
                        }))
            .isInstanceOf(IOException.class)
            .hasMessage("simulated crash before the answer");
        assertThat(Files.readAllBytes(log)).isEqualTo(concat(before, line));
      } else {
        assertThatThrownBy(
                () ->
                    appender.withMissionLock(
                        gcd,
                        mission,
                        System.nanoTime() + 5_000_000_000L,
                        session -> {
                          session.appendAndSync(line);
                          appendReturned.set(true);
                          return null;
                        }))
            .isInstanceOf(PersistenceFailedException.class);
        assertThat(Files.readAllBytes(log)).as("iteration %d (%s) leaves no partial unit", iteration, fault).isEqualTo(before);
      }
      assertThat(appendReturned.get()).as("a normal return requires a successful sync").isEqualTo(files.forceReturnedNormally);
      if (appendReturned.get()) {
        assertThat(files.forceReturnedNormally).isTrue();
      }
    }

    System.out.println("status-log fault injection: iterations=" + ITERATIONS + ", per-fault=" + counts);
    assertThat(counts.values()).allSatisfy(count -> assertThat(count).isEqualTo(30));
  }

  @Test
  void rollbackFailureIsSuppressedAndNeverReportedAsDurable() throws Exception {
    Path gcd = Files.createDirectories(repository.resolve(".git"));
    Path mission = Files.createDirectories(repository.resolve("kitty-specs/mission"));
    Path log = mission.resolve("status.events.jsonl");
    Files.writeString(log, "before\n");
    FaultyFileOps files = new FaultyFileOps(Fault.FORCE);
    files.failTruncate = true;
    StatusLogAppender appender = new StatusLogAppender(LedgerLocks.forThisOs(), files);

    assertThatThrownBy(
            () ->
                appender.withMissionLock(
                    gcd,
                    mission,
                    System.nanoTime() + 5_000_000_000L,
                    session -> {
                      session.appendAndSync("after\n".getBytes(StandardCharsets.UTF_8));
                      return null;
                    }))
        .isInstanceOf(PersistenceFailedException.class)
        .satisfies(
            thrown ->
                assertThat(thrown.getSuppressed())
                    .as("the failed truncate is retained")
                    .anySatisfy(suppressed -> assertThat(suppressed).hasMessage("injected truncate failure")));
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] joined = new byte[first.length + second.length];
    System.arraycopy(first, 0, joined, 0, first.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    return joined;
  }

  private enum Fault {
    WRITE,
    SHORT,
    FORCE,
    KILL_AFTER_SYNC
  }

  private static final class FaultyFileOps implements FileOps {
    private final Fault fault;
    private boolean forceReturnedNormally;
    private boolean failTruncate;
    private boolean rollbackStarted;
    private int forceCalls;

    private FaultyFileOps(Fault fault) {
      this.fault = fault;
    }

    @Override
    public boolean exists(Path path) {
      return Files.exists(path);
    }

    @Override
    public byte[] read(Path path) throws IOException {
      if (!Files.exists(path) && Files.isDirectory(path.getParent())) {
        return new byte[0];
      }
      return Files.readAllBytes(path);
    }

    @Override
    public AppendChannel openForAppend(Path path) throws IOException {
      FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
      return new AppendChannel() {
        @Override
        public long size() throws IOException {
          return channel.size();
        }

        @Override
        public int write(ByteBuffer bytes) throws IOException {
          if (fault == Fault.WRITE) {
            throw new IOException("injected write failure");
          }
          if (fault == Fault.SHORT) {
            return channel.write(bytes.slice(0, Math.min(1, bytes.remaining())));
          }
          return channel.write(bytes);
        }

        @Override
        public void force() throws IOException {
          forceCalls++;
          if (fault == Fault.FORCE && forceCalls == 1) {
            throw new IOException("injected force failure");
          }
          channel.force(true);
          if (!rollbackStarted) {
            forceReturnedNormally = true;
          }
        }

        @Override
        public void truncate(long size) throws IOException {
          rollbackStarted = true;
          if (failTruncate) {
            throw new IOException("injected truncate failure");
          }
          channel.truncate(size);
        }

        @Override
        public void close() throws IOException {
          channel.close();
        }
      };
    }

    @Override
    public void syncDirectory(Path directory) throws IOException {
      if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
          channel.force(true);
        }
      }
    }
  }
}
