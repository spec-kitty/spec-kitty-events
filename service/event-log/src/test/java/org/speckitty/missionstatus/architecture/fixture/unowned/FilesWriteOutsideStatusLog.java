package org.speckitty.missionstatus.architecture.fixture.unowned;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Planted violation: Files.write belongs only to the status-log and ledger packages inside event-log. */
public final class FilesWriteOutsideStatusLog {

  private FilesWriteOutsideStatusLog() {}

  public static void write(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes);
  }
}
