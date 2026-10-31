package org.speckitty.commandcli.adapter.out.filesystem;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The single-instance lock of LAUNCHER.md: an exclusive OS lock on {@code <state dir>/service.lock}, held for the life of the process.
 * Only write-service instances take this file (core's launcher never writes it), so the JDK's {@link FileChannel#tryLock()} (an
 * {@code fcntl} record lock on POSIX, {@code LockFileEx} on Windows) excludes across processes without a native downcall.
 *
 * <p>A POSIX record lock is released when ANY descriptor of the file closes in the same process, so the lock file is opened here and
 * nowhere else, exactly once.
 */
public final class ServiceLock implements AutoCloseable {

  /** The lock file's name in the state directory. */
  public static final String NAME = "service.lock";

  private final FileChannel channel;
  private final FileLock lock;

  private ServiceLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  /** Takes the lock, or answers empty when another process (or this one) holds it. */
  public static Optional<ServiceLock> tryAcquire(StateDirectory directory) throws IOException {
    Path file = directory.resolve(NAME);
    FileChannel channel =
        FileChannel.open(
            file,
            java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            OwnerOnly.fileAttributes(file));
    FileLock lock;
    try {
      lock = channel.tryLock();
    } catch (OverlappingFileLockException held) {
      lock = null;
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
    if (lock == null) {
      channel.close();
      return Optional.empty();
    }
    return Optional.of(new ServiceLock(channel, lock));
  }

  public boolean held() {
    return lock.isValid();
  }

  @Override
  public void close() throws IOException {
    try {
      if (lock.isValid()) {
        lock.release();
      }
    } finally {
      channel.close();
    }
  }
}
