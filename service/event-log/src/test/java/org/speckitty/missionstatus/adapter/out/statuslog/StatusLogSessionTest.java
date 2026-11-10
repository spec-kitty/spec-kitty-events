package org.speckitty.missionstatus.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;

class StatusLogSessionTest {

  @TempDir Path repository;

  private Path gcd;
  private Path mission;
  private Path log;
  private RecordingFileOps files;
  private StatusLogAppender appender;

  @BeforeEach
  void setUp() throws IOException {
    gcd = Files.createDirectories(repository.resolve(".git"));
    mission = Files.createDirectories(repository.resolve("kitty-specs/mission"));
    log = mission.resolve("status.events.jsonl");
    files = new RecordingFileOps();
    appender = new StatusLogAppender(LedgerLocks.forThisOs(), files);
  }

  @Test
  void appendsEveryByteInOneCallThenForcesTheFile() throws Exception {
    Files.writeString(log, "old\n");
    byte[] addition = "{\"batch\": [1, 2, 3]}\n".getBytes(StandardCharsets.UTF_8);

    append(addition);

    assertThat(files.events).containsExactly("open", "size", "write", "force", "close");
    assertThat(files.writes).containsExactly(addition);
    assertThat(Files.readAllBytes(log)).isEqualTo(concat("old\n".getBytes(StandardCharsets.UTF_8), addition));
  }

  @Test
  void syncingANewFileIsFollowedBySyncingItsParentDirectory() throws Exception {
    append("{\"first\": true}\n".getBytes(StandardCharsets.UTF_8));

    assertThat(files.events).containsExactly("open", "size", "write", "force", "directory-sync", "close");
  }

  @Test
  void appendCanRunOnlyOncePerSession() throws Exception {
    appender.withMissionLock(
        gcd,
        mission,
        System.nanoTime() + 5_000_000_000L,
        session -> {
          session.appendAndSync("one\n".getBytes(StandardCharsets.UTF_8));
          assertThatIllegalStateException().isThrownBy(() -> session.appendAndSync("two\n".getBytes(StandardCharsets.UTF_8)));
          return null;
        });
    assertThat(files.writes).hasSize(1);
  }

  @Test
  void rejectsAnEmptyAppendAndAnAppendWithoutALineFeed() {
    assertThatIllegalArgumentException().isThrownBy(() -> append(new byte[0]));
    assertThatIllegalArgumentException().isThrownBy(() -> append("not-a-full-line".getBytes(StandardCharsets.UTF_8)));
    assertThat(files.events).isEmpty();
  }

  private void append(byte[] bytes) throws IOException {
    appender.withMissionLock(
        gcd,
        mission,
        System.nanoTime() + 5_000_000_000L,
        session -> {
          session.appendAndSync(bytes);
          return null;
        });
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] joined = new byte[first.length + second.length];
    System.arraycopy(first, 0, joined, 0, first.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    return joined;
  }

  private static final class RecordingFileOps implements FileOps {
    private final List<String> events = new ArrayList<>();
    private final List<byte[]> writes = new ArrayList<>();

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
    public boolean regularOrAbsent(Path path) {
      return !Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public AppendChannel openForAppend(Path path) throws IOException {
      events.add("open");
      FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
      return new AppendChannel() {
        @Override
        public long size() throws IOException {
          events.add("size");
          return channel.size();
        }

        @Override
        public int write(ByteBuffer bytes) throws IOException {
          events.add("write");
          byte[] copy = new byte[bytes.remaining()];
          bytes.duplicate().get(copy);
          writes.add(copy);
          return channel.write(bytes);
        }

        @Override
        public void force() throws IOException {
          events.add("force");
          channel.force(true);
        }

        @Override
        public void truncate(long size) throws IOException {
          events.add("truncate");
          channel.truncate(size);
        }

        @Override
        public void close() throws IOException {
          events.add("close");
          channel.close();
        }
      };
    }

    @Override
    public void syncDirectory(Path directory) {
      events.add("directory-sync");
    }
  }
}
