package org.speckitty.commandcli.adapter.out.statuslog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.missionstatus.adapter.out.ledger.LockAbandonedException;
import org.speckitty.missionstatus.adapter.out.statuslog.StatusLogAppender;
import org.speckitty.missionstatus.adapter.out.statuslog.StatusLockHeldException;
import org.speckitty.missionstatus.adapter.out.statuslog.TailRepair;

/** Translates event-log lock and persistence failures into application-port outcomes. */
public final class EventLogStatusLog implements StatusLog {
  private final StatusLogAppender appender;
  private final Path gcd;
  private final Path root;

  public EventLogStatusLog(StatusLogAppender appender, Path gcd, Path root) {
    this.appender = appender;
    this.gcd = gcd;
    this.root = root;
  }

  @Override
  public <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, SessionWork<T> work) {
    return withLock(mission, deadlineNanos, () -> false, work);
  }

  @Override
  public <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, BooleanSupplier abandon, SessionWork<T> work) {
    try {
      return appender.withMissionLock(gcd, root.resolve(mission.directory()), deadlineNanos, abandon, locked -> {
        try {
          return new LockResult.Completed<>(work.run(new Session() {
            @Override
            public byte[] currentBytes() {
              try {
                return locked.currentBytes();
              } catch (IOException exception) {
                throw new LogIOException(exception);
              }
            }

            @Override
            public void appendAndSync(byte[] bytes) {
              try {
                locked.appendAndSync(bytes);
              } catch (IOException exception) {
                throw new LogIOException(exception);
              }
            }

            @Override
            public RepairResult repairTail(TailCheck check) {
              try {
                TailRepair repair = locked.repairTail(check::isCompleteRow);
                return new RepairResult(RepairResult.Action.valueOf(repair.action().name()), repair.movedBytes(), repair.bytesAfter());
              } catch (IOException exception) {
                throw new LogIOException(exception);
              }
            }
          }));
        } catch (LogIOException exception) {
          throw exception;
        }
      });
    } catch (StatusLockHeldException held) {
      return new LockResult.LockHeld<>();
    } catch (LockAbandonedException abandoned) {
      return new LockResult.Abandoned<>();
    } catch (IOException | LogIOException failure) {
      return new LockResult.PersistenceFailed<>();
    }
  }

  private static final class LogIOException extends RuntimeException {
    private LogIOException(IOException cause) {
      super(cause);
    }
  }
}
