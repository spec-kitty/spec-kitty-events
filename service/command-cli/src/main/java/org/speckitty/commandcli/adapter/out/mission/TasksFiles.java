package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Core's work-package task-file lookup, with its distinct dependency and roster matching rules. */
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

  public static List<Path> dependencies(Path mission, String wpId) {
    return paths(mission, wpId, false);
  }

  public static List<Path> rosters(Path mission, String wpId) {
    return paths(mission, wpId, true);
  }

  public static List<String> dependencyNames(Collection<String> filenames, String wpId) {
    return matching(filenames, wpId, false);
  }

  public static List<String> rosterNames(Collection<String> filenames, String wpId) {
    return matching(filenames, wpId, true);
  }

  private static List<Path> paths(Path mission, String wpId, boolean roster) {
    Path tasks = mission.resolve("tasks");
    try (Stream<Path> paths = Files.list(tasks)) {
      List<String> names = paths.map(path -> path.getFileName().toString()).toList();
      List<String> matches = roster ? rosterNames(names, wpId) : dependencyNames(names, wpId);
      return matches.stream().map(tasks::resolve).toList();
    } catch (IOException exception) {
      return List.of();
    }
  }

  private static List<String> matching(Collection<String> filenames, String wpId, boolean ignoreCase) {
    int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
    Pattern pattern = Pattern.compile("^" + Pattern.quote(wpId) + "(?:[-_.]|\\.md$)", flags);
    return filenames.stream()
        .filter(filename -> filename.endsWith(".md"))
        .filter(filename -> !filename.equalsIgnoreCase("readme.md"))
        .filter(filename -> pattern.matcher(filename).find())
        .sorted()
        .toList();
  }
}
