package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Core's work-package task-file lookup and declaration-file matching. */
public final class TasksFiles {
  private TasksFiles() {}

  public static boolean exists(Path mission, String wpId) {
    Path tasks = mission.resolve("tasks");
    if (Files.isRegularFile(tasks.resolve(wpId + ".md"))) return true;
    try (Stream<Path> paths = Files.list(tasks)) {
      return paths.anyMatch(path -> path.getFileName().toString().startsWith(wpId + "-")
          && path.getFileName().toString().endsWith(".md") && Files.isRegularFile(path));
    } catch (IOException exception) {
      return false;
    }
  }

  public static List<Path> declarations(Path mission, String wpId) {
    Path tasks = mission.resolve("tasks");
    Pattern match = Pattern.compile("^" + Pattern.quote(wpId) + "(?:[-_.]|\\.md$)");
    try (Stream<Path> paths = Files.list(tasks)) {
      return paths.filter(Files::isRegularFile)
          .filter(path -> !path.getFileName().toString().equalsIgnoreCase("readme.md"))
          .filter(path -> match.matcher(path.getFileName().toString()).find())
          .sorted()
          .toList();
    } catch (IOException exception) {
      return List.of();
    }
  }
}
