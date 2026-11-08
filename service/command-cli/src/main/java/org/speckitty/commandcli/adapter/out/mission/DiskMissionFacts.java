package org.speckitty.commandcli.adapter.out.mission;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.MissionFacts;

/** Production MissionFacts read from task frontmatter while the Mission lock is held. */
public final class DiskMissionFacts implements MissionFacts {
  private final Path mission;

  public DiskMissionFacts(Path mission) {
    this.mission = mission;
  }

  @Override
  public DependencyDeclaration dependencies(String wpId) {
    List<Path> matches = TasksFiles.declarations(mission, wpId);
    if (matches.isEmpty()) return new DependencyDeclaration.None();
    if (matches.size() != 1) return new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies");
    Optional<Map<String, Object>> frontmatter = Frontmatter.read(matches.getFirst());
    if (frontmatter.isEmpty()) return new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies");
    Object value = frontmatter.orElseThrow().get("dependencies");
    if (value instanceof List<?> list) return strings(list);
    if (value instanceof String text) return stringDeclaration(text);
    return value == null ? new DependencyDeclaration.None()
        : new DependencyDeclaration.Unresolvable("dependencies is neither a string nor a list");
  }

  @Override
  public List<String> subtaskRoster(String wpId) {
    List<Path> matches = TasksFiles.declarations(mission, wpId);
    if (matches.size() != 1) return List.of();
    Object value = Frontmatter.read(matches.getFirst()).map(map -> map.get("subtasks")).orElse(null);
    if (!(value instanceof List<?> list)) return List.of();
    List<String> roster = new ArrayList<>();
    for (Object item : list) if (item instanceof String id) roster.add(id);
    return List.copyOf(roster);
  }

  private static DependencyDeclaration stringDeclaration(String text) {
    String stripped = text.strip();
    if (stripped.isEmpty() || stripped.equals("[]")) return new DependencyDeclaration.None();
    return strings(List.of(stripped.split("\\s*,\\s*")));
  }

  private static DependencyDeclaration strings(List<?> values) {
    List<String> ids = new ArrayList<>();
    for (Object value : values) {
      if (!(value instanceof String id)) return new DependencyDeclaration.Unresolvable("invalid dependency declaration");
      if (!id.strip().isEmpty()) ids.add(id.strip());
    }
    return ids.isEmpty() ? new DependencyDeclaration.None() : new DependencyDeclaration.Declared(ids);
  }
}
