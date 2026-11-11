package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Session state, the one-write durability protocol and the one-shot tail repair for one lock hold. */
final class LockedStatusLogSession implements StatusLogSession {

  private static final Path TORN_NAME = Path.of("status.events.jsonl.torn");

  private final Path log;
  private final FileOps files;
  private boolean mutationAttempted;
  private boolean closed;

  LockedStatusLogSession(Path log, FileOps files) {
    this.log = log;
    this.files = files;
  }

  @Override
  public byte[] currentBytes() throws IOException {
    if (closed) {
      throw new IOException("the status log session is closed");
    }
    return readAndCheckTail();
  }

  @Override
  public void appendAndSync(byte[] bytes) throws IOException {
    if (closed || mutationAttempted) {
      throw new IllegalStateException("a status log session permits one append before it closes");
    }
    if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
      throw new IllegalArgumentException("a status append must contain non-empty, LF-terminated lines");
    }
    mutationAttempted = true;
    byte[] current = readAndCheckTail();
    boolean existed = files.exists(log);
    try (FileOps.AppendChannel channel = files.openForAppend(log)) {
      long sizeBefore = channel.size();
      if (sizeBefore != current.length) {
        throw new PersistenceFailedException(log, new IOException("the status log changed after its tail was verified"));
      }
      try {
        writeAndSync(channel, bytes, existed);
      } catch (IOException | RuntimeException failure) {
        throw rollback(channel, sizeBefore, failure);
      }
    }
  }

  @Override
  public TailRepair repairTail(TailClassifier classifier) throws IOException {
    Objects.requireNonNull(classifier, "classifier");
    if (closed || mutationAttempted) {
      throw new IllegalStateException("a status log session permits one append or one repair before it closes");
    }
    mutationAttempted = true;
    try {
      return repair(classifier);
    } catch (PersistenceFailedException failure) {
      // Only the repair's own failures are path-free; an appender-shaped failure (e.g. a symlinked log refused by read) names a
      // host path, so it is wrapped rather than passed through (the port promises no host path in a repair message).
      throw failure.isRepairFailure() ? failure : PersistenceFailedException.repairFailed(failure);
    } catch (IOException | RuntimeException failure) {
      throw PersistenceFailedException.repairFailed(failure);
    }
  }

  void close() {
    closed = true;
  }

  // ---- tail repair -------------------------------------------------------------------------------------------------------
  // Status logs are ASCII (core's status_log.py writes ensure_ascii JSON), so cutting at an LF byte never splits a code point.
  // Byte conservation (NFR-001): every byte of the original log is in the log or in the quarantine file after any single fault.

  private TailRepair repair(TailClassifier classifier) throws IOException {
    // A missing log reads as empty and creates nothing; a symlinked log is refused by read.
    byte[] bytes = files.read(log);
    if (bytes.length == 0 || bytes[bytes.length - 1] == '\n') {
      // NONE: no open, no write, no file created.
      return new TailRepair(TailRepair.Action.NONE, 0L, bytes);
    }
    int lastLf = lastLineFeed(bytes);
    try (FileOps.AppendChannel channel = files.openForAppend(log)) {
      // Foreign-writer guard, before the first mutation: the log must still be exactly what was read.
      requireUnchanged(channel, bytes.length);
      boolean complete = isCompleteRow(classifier, bytes, lastLf);
      return complete ? complete(channel, bytes) : quarantine(channel, bytes, lastLf);
    }
  }

  private static int lastLineFeed(byte[] bytes) {
    int index = bytes.length - 1;
    while (index >= 0 && bytes[index] != '\n') {
      index--;
    }
    return index;
  }

  private static boolean isCompleteRow(TailClassifier classifier, byte[] bytes, int lastLf) {
    // The callee gets private copies, so nothing it does can alter what is later written or reported.
    byte[] prefix = Arrays.copyOfRange(bytes, 0, lastLf + 1);
    byte[] fragment = Arrays.copyOfRange(bytes, lastLf + 1, bytes.length);
    try {
      return classifier.isCompleteRow(prefix, fragment);
    } catch (RuntimeException unclassifiable) {
      return false;
    }
  }

  private void requireUnchanged(FileOps.AppendChannel channel, int expectedSize) throws IOException {
    if (channel.size() != expectedSize) {
      throw PersistenceFailedException.repairFailed(new IOException("the status log changed after it was read"));
    }
  }

  /** COMPLETED: append exactly one LF. A failure truncates the LF away again, so the log is the original or the original plus LF. */
  private TailRepair complete(FileOps.AppendChannel channel, byte[] bytes) throws IOException {
    // Second foreign-writer guard, after the (slow) classifier and before the LF: outside the try, so a refusal here never
    // truncates bytes a foreign writer appended.
    requireUnchanged(channel, bytes.length);
    try {
      writeFully(channel, new byte[] {'\n'});
      channel.force();
    } catch (IOException | RuntimeException failure) {
      throw restore(channel, bytes.length, failure);
    }
    byte[] after = Arrays.copyOf(bytes, bytes.length + 1);
    after[bytes.length] = '\n';
    return new TailRepair(TailRepair.Action.COMPLETED, 0L, after);
  }

  /**
   * QUARANTINED. Order: (1) vet the quarantine path, (2) fragment + LF appended to it and forced, (3) its directory synced, (4) the log
   * size re-checked, (5) the log truncated to its last LF, (6) the log forced, (7) the directory synced. The truncate is the only
   * step that removes bytes from the log, so a failure before it leaves the log byte-identical (the partial quarantine write is
   * rolled back), and a failure after it leaves the fragment durable in quarantine.
   */
  private TailRepair quarantine(FileOps.AppendChannel logChannel, byte[] bytes, int lastLf) throws IOException {
    Path torn = log.resolveSibling(TORN_NAME);
    // Residual risk (accepted): the JDK has no non-blocking open, so a FIFO swapped in between this check and the open below
    // would block the open while the Mission lock is held. The check closes every non-racing case.
    if (!files.regularOrAbsent(torn)) {
      // A symlink, directory or FIFO: never open it (a FIFO would block under the lock). Nothing has changed.
      throw PersistenceFailedException.repairFailed(new IOException("the quarantine path is not a regular file"));
    }
    try (FileOps.AppendChannel tornChannel = files.openForAppend(torn)) {
      long tornStart = tornChannel.size();
      try {
        copyFragment(tornChannel, bytes, lastLf, tornStart > 0 && !endsWithLf(files.read(torn)));
        requireUnchanged(logChannel, bytes.length);
      } catch (IOException | RuntimeException failure) {
        throw restore(tornChannel, tornStart, failure);
      }
      truncateLog(logChannel, tornChannel, tornStart, bytes.length, lastLf + 1);
    }
    return new TailRepair(TailRepair.Action.QUARANTINED, bytes.length - (lastLf + 1), Arrays.copyOf(bytes, lastLf + 1));
  }

  /**
   * Appends the fragment plus a LF in one write. When the existing quarantine does not end in LF (a power loss before its force, or a
   * hand edit), a separating LF leads the same write, so the fragment never joins the previous line and a rollback removes both.
   */
  private void copyFragment(FileOps.AppendChannel tornChannel, byte[] bytes, int lastLf, boolean separate) throws IOException {
    int lead = separate ? 1 : 0;
    byte[] entry = new byte[lead + bytes.length - (lastLf + 1) + 1];
    if (separate) {
      entry[0] = '\n';
    }
    System.arraycopy(bytes, lastLf + 1, entry, lead, bytes.length - (lastLf + 1));
    entry[entry.length - 1] = '\n';
    writeFully(tornChannel, entry);
    tornChannel.force();
    // Always, new file or not: the fragment must be durable and reachable before the log gives it up.
    files.syncDirectory(log.getParent());
  }

  /** Removes the fragment from the log. Only here may bytes leave the log, and only after the quarantine copy is durable. */
  private void truncateLog(
      FileOps.AppendChannel logChannel, FileOps.AppendChannel tornChannel, long tornStart, long originalSize, long keep)
      throws IOException {
    try {
      logChannel.truncate(keep);
    } catch (IOException | RuntimeException failure) {
      // The call may or may not have taken effect. Only a verified untouched log lets the quarantine copy be withdrawn.
      if (logIsUntouched(logChannel, originalSize)) {
        throw restore(tornChannel, tornStart, failure);
      }
      throw PersistenceFailedException.repairFailed(failure);
    }
    try {
      logChannel.force();
      files.syncDirectory(log.getParent());
    } catch (IOException | RuntimeException failure) {
      // The fragment is durable in quarantine and the log is at its last LF; a re-run answers NONE. Never roll the quarantine back.
      throw PersistenceFailedException.repairFailed(failure);
    }
  }

  private static boolean endsWithLf(byte[] content) {
    return content.length > 0 && content[content.length - 1] == '\n';
  }

  private static boolean logIsUntouched(FileOps.AppendChannel logChannel, long originalSize) {
    try {
      return logChannel.size() == originalSize;
    } catch (IOException | RuntimeException unknown) {
      return false;
    }
  }

  private static void writeFully(FileOps.AppendChannel channel, byte[] bytes) throws IOException {
    int written = channel.write(ByteBuffer.wrap(bytes));
    if (written != bytes.length) {
      throw new IOException("short write: " + written + " of " + bytes.length + " bytes");
    }
  }

  /** Truncates {@code channel} back to {@code size} and forces it, best effort, and returns the path-free failure to throw. */
  private static PersistenceFailedException restore(FileOps.AppendChannel channel, long size, Exception cause) {
    PersistenceFailedException failure =
        cause instanceof PersistenceFailedException already ? already : PersistenceFailedException.repairFailed(cause);
    try {
      channel.truncate(size);
      channel.force();
    } catch (IOException | RuntimeException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
    return failure;
  }

  private byte[] readAndCheckTail() throws IOException {
    byte[] current = files.read(log);
    if (current.length > 0 && current[current.length - 1] != '\n') {
      throw new TornTailException(log);
    }
    return current;
  }

  private void writeAndSync(FileOps.AppendChannel channel, byte[] bytes, boolean existed) throws IOException {
    int written = channel.write(ByteBuffer.wrap(bytes));
    if (written != bytes.length) {
      throw new IOException("short status log append: " + written + " of " + bytes.length + " bytes");
    }
    channel.force();
    if (!existed) {
      files.syncDirectory(log.getParent());
    }
  }

  private PersistenceFailedException rollback(FileOps.AppendChannel channel, long sizeBefore, Exception cause) {
    PersistenceFailedException failure = new PersistenceFailedException(log, cause);
    try {
      channel.truncate(sizeBefore);
      channel.force();
    } catch (IOException | RuntimeException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
    return failure;
  }
}
