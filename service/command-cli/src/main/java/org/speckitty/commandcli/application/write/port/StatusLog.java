package org.speckitty.commandcli.application.write.port;

import java.util.function.BooleanSupplier;

/** Byte-oriented, locked status log storage. */
public interface StatusLog {
  sealed interface LockResult<T> permits LockResult.Completed, LockResult.LockHeld, LockResult.Abandoned, LockResult.PersistenceFailed {
    record Completed<T>(T value) implements LockResult<T> {}
    record LockHeld<T>() implements LockResult<T> {}

    /** The wait for the Mission lock was given up because the service began to drain (D10); nothing was written. */
    record Abandoned<T>() implements LockResult<T> {}
    record PersistenceFailed<T>() implements LockResult<T> {}
  }

  @FunctionalInterface
  interface SessionWork<T> {
    T run(Session session);
  }

  /** What a tail repair did: its action, the fragment length it moved (0 unless quarantined) and the whole log it left. */
  record RepairResult(Action action, long movedBytes, byte[] bytesAfter) {
    public enum Action { NONE, COMPLETED, QUARANTINED }
  }

  /** Decides whether the fragment of a torn log is exactly one status row that folds after the prefix; false on any doubt. */
  @FunctionalInterface
  interface TailCheck {
    boolean isCompleteRow(byte[] prefix, byte[] fragment);
  }

  interface Session {
    byte[] currentBytes();
    void appendAndSync(byte[] bytes);

    /** The explicit, locked tail repair; never part of a write. A store without one keeps this refusal. */
    default RepairResult repairTail(TailCheck check) {
      throw new UnsupportedOperationException("repair");
    }
  }

  <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, SessionWork<T> work);

  /**
   * As {@link #withLock(MissionRef, long, SessionWork)}, but a wait for the Mission lock ends in {@link LockResult.Abandoned} once
   * {@code abandon} is true (D10: a draining service stops waiting). {@code abandon} is consulted only while waiting, never once the
   * lock is held. A store that cannot abandon keeps this default, which ignores the signal.
   */
  default <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, BooleanSupplier abandon, SessionWork<T> work) {
    return withLock(mission, deadlineNanos, work);
  }
}
