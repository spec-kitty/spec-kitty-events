package org.speckitty.missionstatus.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The repository's root Gradle build, copied for the nested-build (TestKit) tests. In core the service was a standalone build; here it
 * is the project {@code :mission-status-api} of the root build (#147), so a nested build needs the root build's inputs: its settings
 * and build scripts, gradle.properties, gradle/ (the version catalog and the verification metadata), the build-logic sources, and a
 * build script for every other project the root settings include. Never sources or build output; each test adds what it compiles.
 */
public final class NestedRootBuild {

  /** The task path a nested build runs. */
  public static final String COMPILE_SERVICE = ":mission-status-api:compileJava";

  /** The service project directory, relative to the root. */
  public static final String SERVICE = "service";

  private static final List<String> ROOT_FILES = List.of("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat");

  /** The build scripts of the projects the root settings include besides the service ones: present, so the copy configures. */
  private static final List<String> OTHER_PROJECT_SCRIPTS =
      List.of("java/build.gradle.kts", "contracts/build.gradle", "service/command-cli/build.gradle.kts");

  private static final Set<String> BUILD_LOGIC_SKIPPED = Set.of("build", ".gradle", ".kotlin");

  private NestedRootBuild() {}

  /** Copies the root build inputs from {@code root} into {@code copy} and returns the copy's service project directory (created, empty). */
  public static Path copyRootBuild(Path root, Path copy) throws IOException {
    Files.createDirectories(copy);
    for (String name : ROOT_FILES) {
      Files.copy(root.resolve(name), copy.resolve(name));
    }
    copyTree(root.resolve("gradle"), copy.resolve("gradle"));
    Path buildLogic = root.resolve("build-logic");
    try (Stream<Path> entries = Files.list(buildLogic)) {
      for (Path entry : (Iterable<Path>) entries::iterator) {
        String name = entry.getFileName().toString();
        if (!BUILD_LOGIC_SKIPPED.contains(name)) {
          copyTree(entry, copy.resolve("build-logic").resolve(name));
        }
      }
    }
    for (String script : OTHER_PROJECT_SCRIPTS) {
      Path target = copy.resolve(script);
      Files.createDirectories(target.getParent());
      Files.copy(root.resolve(script), target);
    }
    return Files.createDirectories(copy.resolve(SERVICE));
  }

  /** Copies a file, or a directory tree, to {@code to}. */
  public static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path path : (Iterable<Path>) paths::iterator) {
        Path target = to.resolve(from.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(path, target);
        }
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
