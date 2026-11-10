package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** JDK filesystem operations used in production. */
final class JdkFileOps implements FileOps {

  @Override
  public boolean exists(Path path) {
    return Files.exists(path);
  }

  @Override
  public byte[] read(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      return Channels.newInputStream(channel).readAllBytes();
    } catch (NoSuchFileException e) {
      if (Files.isSymbolicLink(path)) {
        throw new PersistenceFailedException(path, e);
      }
      if (Files.isDirectory(path.getParent())) {
        return new byte[0];
      }
      throw e;
    } catch (IOException e) {
      throw mapOpenFailure(path, e);
    }
  }

  @Override
  public AppendChannel openForAppend(Path path) throws IOException {
    FileChannel channel;
    try {
      channel =
          FileChannel.open(
              path,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND,
              LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      throw mapOpenFailure(path, e);
    }
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

  @Override
  public boolean regularOrAbsent(Path path) throws IOException {
    try {
      return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile();
    } catch (NoSuchFileException e) {
      return true;
    }
  }

  private IOException mapOpenFailure(Path path, IOException failure) {
    if (Files.isSymbolicLink(path)) {
      return new PersistenceFailedException(path, failure);
    }
    return failure;
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
