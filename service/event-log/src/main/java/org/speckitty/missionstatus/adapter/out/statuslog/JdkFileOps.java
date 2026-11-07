package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** JDK filesystem operations used in production. */
final class JdkFileOps implements FileOps {

  @Override
  public boolean exists(Path path) {
    return Files.exists(path);
  }

  @Override
  public byte[] read(Path path) throws IOException {
    try {
      return Files.readAllBytes(path);
    } catch (NoSuchFileException e) {
      if (Files.isDirectory(path.getParent())) {
        return new byte[0];
      }
      throw e;
    }
  }

  @Override
  public AppendChannel openForAppend(Path path) throws IOException {
    FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    return new AppendChannel() {
      @Override
      public long size() throws IOException {
        return channel.size();
      }

      @Override
      public int write(ByteBuffer bytes) throws IOException {
        return channel.write(bytes);
      }

      @Override
      public void force() throws IOException {
        channel.force(true);
      }

      @Override
      public void truncate(long size) throws IOException {
        channel.truncate(size);
      }

      @Override
      public void close() throws IOException {
        channel.close();
      }
    };
  }

  /** On Windows a directory channel cannot be opened; NTFS journals the new directory entry, so no separate sync is needed. */
  @Override
  public void syncDirectory(Path directory) throws IOException {
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      return;
    }
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }
}
