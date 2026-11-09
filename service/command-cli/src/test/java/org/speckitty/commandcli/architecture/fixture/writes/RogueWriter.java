package org.speckitty.commandcli.architecture.fixture.writes;

import java.nio.file.Files;
import java.nio.file.Path;

/** Planted FR-010 violation: a class outside the state-directory adapter opens a file for writing. */
public final class RogueWriter {
  private RogueWriter() {}

  public static void write(Path path) throws Exception {
    Files.writeString(path, "rogue");
  }
}
