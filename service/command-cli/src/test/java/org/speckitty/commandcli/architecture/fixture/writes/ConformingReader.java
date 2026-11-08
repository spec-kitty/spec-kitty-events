package org.speckitty.commandcli.architecture.fixture.writes;

import java.nio.file.Files;
import java.nio.file.Path;

/** Reading does not match the shared open-for-write predicate. */
public final class ConformingReader {
  private ConformingReader() {}

  public static String read(Path path) throws Exception {
    return Files.readString(path);
  }
}
