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
