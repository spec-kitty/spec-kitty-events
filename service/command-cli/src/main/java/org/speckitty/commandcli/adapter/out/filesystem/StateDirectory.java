package org.speckitty.commandcli.adapter.out.filesystem;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The write service's own directory in the git common dir, {@code <gcd>/spec-kitty-write-service/} (LAUNCHER.md; core
 * {@code ENDPOINT_DIRECTORY}). It holds {@code service.lock}, {@code endpoint.json} and the launcher's {@code service.log}. It must be a
 * real directory, never a symbolic link, with owner-only access: core's launcher creates it 0700 and its endpoint reader refuses a
 * directory others can reach, so the service tightens a loose mode and refuses a link.
 */
public final class StateDirectory {

  /** The directory's name in the git common dir. */
  public static final String NAME = "spec-kitty-write-service";

  private final Path path;

  private StateDirectory(Path path) {
    this.path = path;
  }

  /** Prepares {@code <gitCommonDir>/spec-kitty-write-service}: creates it owner-only, tightens a loose mode, refuses a link. */
  public static StateDirectory prepare(Path gitCommonDir) throws StateDirectoryRefused {
    Path directory = gitCommonDir.resolve(NAME);
    try {
      try {
        Files.createDirectory(directory, OwnerOnly.directoryAttributes(directory));
        OwnerOnly.restrict(directory, true);
      } catch (FileAlreadyExistsException exists) {
        // checked below without following links
      }
      BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink()) {
        throw new StateDirectoryRefused(NAME + " is a symbolic link; the write service never follows it");
      }
      if (!attributes.isDirectory()) {
        throw new StateDirectoryRefused(NAME + " exists and is not a directory");
      }
      if (OwnerOnly.exposure(directory).isPresent()) {
        OwnerOnly.restrict(directory, true);
      }
      if (OwnerOnly.exposure(directory).isPresent()) {
        throw new StateDirectoryRefused(NAME + " stays reachable by other users");
      }
    } catch (IOException e) {
      throw new StateDirectoryRefused(NAME + " cannot be prepared (" + e.getClass().getSimpleName() + ")");
    }
    return new StateDirectory(directory);
  }

  public Path path() {
    return path;
  }

  public Path resolve(String name) {
    return path.resolve(name);
  }
}
