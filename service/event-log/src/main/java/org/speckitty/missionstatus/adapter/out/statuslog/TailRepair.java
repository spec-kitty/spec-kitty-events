package org.speckitty.missionstatus.adapter.out.statuslog;

import java.util.Arrays;
import java.util.Objects;

/**
 * What a locked tail repair did to a status log.
 *
 * <p>Invariant (byte conservation): under any single fault, every byte of the original log survives either in the log or in the
 * quarantine file {@code status.events.jsonl.torn}. Durability order of a {@link Action#QUARANTINED} repair: the fragment and an LF are
 * appended to the quarantine file and forced, its directory is synced, and only then is the log truncated to its last LF, forced and
 * its directory synced. The truncate is the only step that removes bytes from the log. A quarantine that fails before its first
 * write may leave an empty quarantine file behind ({@code FileOps} has no delete); it holds no bytes, so conservation still holds.
 *
 * @param action what the repair did
 * @param movedBytes the fragment length for {@code QUARANTINED} (without the LF written after it), otherwise 0
 * @param bytesAfter the whole log after the repair; the record owns a private copy, and each accessor call returns a fresh one
 */
public record TailRepair(Action action, long movedBytes, byte[] bytesAfter) {

  /** The three outcomes. */
  public enum Action {
    /** The log was missing, empty or ended in LF; nothing was touched and no file was created. */
    NONE,
    /** The fragment was one complete row; exactly one LF was appended to the log. */
    COMPLETED,
    /** The fragment was moved to the quarantine file and the log truncated to its last LF. */
    QUARANTINED
  }

  public TailRepair {
    Objects.requireNonNull(action, "action");
    bytesAfter = bytesAfter.clone();
  }

  @Override
  public byte[] bytesAfter() {
    return bytesAfter.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof TailRepair that
        && action == that.action
        && movedBytes == that.movedBytes
        && Arrays.equals(bytesAfter, that.bytesAfter);
  }

  @Override
  public int hashCode() {
    return Objects.hash(action, movedBytes, Arrays.hashCode(bytesAfter));
  }

  @Override
  public String toString() {
    return "TailRepair[action=" + action + ", movedBytes=" + movedBytes + ", bytesAfter=" + bytesAfter.length + " bytes]";
  }
}
