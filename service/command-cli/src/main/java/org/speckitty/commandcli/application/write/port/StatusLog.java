package org.speckitty.commandcli.application.write.port;

/** Byte-oriented, locked status log storage. */
public interface StatusLog {
  sealed interface LockResult<T> permits LockResult.Completed, LockResult.LockHeld, LockResult.PersistenceFailed {
    record Completed<T>(T value) implements LockResult<T> {}
    record LockHeld<T>() implements LockResult<T> {}
    record PersistenceFailed<T>() implements LockResult<T> {}
  }

  @FunctionalInterface
  interface SessionWork<T> {
    T run(Session session);
  }

  interface Session {
    byte[] currentBytes();
    void appendAndSync(byte[] bytes);
  }

  <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, SessionWork<T> work);
}
