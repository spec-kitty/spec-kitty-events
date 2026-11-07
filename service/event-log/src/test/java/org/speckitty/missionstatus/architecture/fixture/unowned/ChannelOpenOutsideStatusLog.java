package org.speckitty.missionstatus.architecture.fixture.unowned;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Planted violation: every FileChannel.open counts because ArchUnit cannot inspect its open options. */
public final class ChannelOpenOutsideStatusLog {

  private ChannelOpenOutsideStatusLog() {}

  public static FileChannel open(Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.READ);
  }
}
