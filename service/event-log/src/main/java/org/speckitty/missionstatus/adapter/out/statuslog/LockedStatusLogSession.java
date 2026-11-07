package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Session state and the one-write durability protocol for one lock hold. */
final class LockedStatusLogSession implements StatusLogSession {

  private final Path log;
  private final FileOps files;
  private boolean appendAttempted;
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
    if (closed || appendAttempted) {
      throw new IllegalStateException("a status log session permits one append before it closes");
    }
    if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
      throw new IllegalArgumentException("a status append must contain non-empty, LF-terminated lines");
    }
    appendAttempted = true;
    readAndCheckTail();
    boolean existed = files.exists(log);
    try (FileOps.AppendChannel channel = files.openForAppend(log)) {
      long sizeBefore = channel.size();
      try {
        writeAndSync(channel, bytes, existed);
      } catch (IOException failure) {
        throw rollback(channel, sizeBefore, failure);
      }
    }
  }

  void close() {
    closed = true;
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

  private PersistenceFailedException rollback(FileOps.AppendChannel channel, long sizeBefore, IOException cause) {
    PersistenceFailedException failure = new PersistenceFailedException(log, cause);
    try {
      channel.truncate(sizeBefore);
      channel.force();
    } catch (IOException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
    return failure;
  }
}
