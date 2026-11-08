package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Core's work-package task-file lookup, with one case-insensitive locator for both guards (R26). */
public final class TasksFiles {
  private TasksFiles() {}

  public static boolean exists(Path mission, String wpId) {
    Path tasks = mission.resolve("tasks");
    Path exact = tasks.resolve(wpId + ".md");
    if (Files.exists(exact)) return Files.isRegularFile(exact);
    try (Stream<Path> paths = Files.list(tasks)) {
      return paths.anyMatch(path -> path.getFileName().toString().startsWith(wpId + "-")
          && path.getFileName().toString().endsWith(".md") && regularOrDangling(path));
    } catch (IOException | UncheckedIOException exception) {
      return false;
    }
  }

  private static boolean regularOrDangling(Path path) {
    return Files.isRegularFile(path) || Files.isSymbolicLink(path) && !Files.exists(path);
  }

  /** R26: one case-insensitive rule for both guards' locators (core's dependency locator is case-sensitive). */
  public static List<Path> paths(Path mission, String wpId) {
    Path tasks = mission.resolve("tasks");
    try (Stream<Path> paths = Files.list(tasks)) {
      List<String> names = paths.map(path -> path.getFileName().toString()).toList();
      return names(names, wpId).stream().map(tasks::resolve).toList();
    } catch (IOException exception) {
      return List.of();
    }
  }

  public static List<String> names(Collection<String> filenames, String wpId) {
    Pattern pattern = Pattern.compile("^" + Pattern.quote(wpId) + "(?:[-_.]|\\.md$)", Pattern.CASE_INSENSITIVE);
    return filenames.stream()
        .filter(filename -> filename.endsWith(".md"))
        .filter(filename -> !filename.equalsIgnoreCase("readme.md"))
        .filter(filename -> pattern.matcher(filename).find())
        .sorted()
        .toList();
  }
}
