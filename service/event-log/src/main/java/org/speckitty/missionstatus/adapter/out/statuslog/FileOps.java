package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Package-private filesystem seam for durability fault injection. */
interface FileOps {

  boolean exists(Path path);

  /** Reads a log; an absent file in an existing directory is empty history. */
  byte[] read(Path path) throws IOException;

  AppendChannel openForAppend(Path path) throws IOException;

  /**
   * True when {@code path} does not exist, or is a regular file that is not reached through a link. A symbolic link (dangling or not),
   * a directory or a FIFO is false. Links are never followed; use this, not {@link #exists}, to vet a path before opening it.
   */
  boolean regularOrAbsent(Path path) throws IOException;

  void syncDirectory(Path directory) throws IOException;

  /** The narrow append-channel operations used by the session. */
  interface AppendChannel extends AutoCloseable {
    long size() throws IOException;

    int write(ByteBuffer bytes) throws IOException;

    void force() throws IOException;

    void truncate(long size) throws IOException;

    @Override
    void close() throws IOException;
  }
}
