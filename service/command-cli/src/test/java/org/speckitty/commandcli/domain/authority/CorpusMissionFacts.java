package org.speckitty.commandcli.domain.authority;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.speckitty.commandcli.adapter.out.mission.FactValues;
import org.speckitty.commandcli.adapter.out.mission.Frontmatter;
import org.speckitty.commandcli.adapter.out.mission.TasksFiles;

/** In-memory MissionFacts using exactly the production frontmatter and value rules. */
final class CorpusMissionFacts implements MissionFacts {
  private final Map<String, Optional<Map<String, Object>>> documents;

  CorpusMissionFacts(Map<String, String> tasks) {
    documents = new HashMap<>();
    tasks.forEach((filename, text) -> documents.put(filename, Frontmatter.readText(filename, text)));
  }

  @Override
  public DependencyDeclaration dependencies(String wpId) {
    List<String> matches = TasksFiles.names(documents.keySet(), wpId);
    if (matches.isEmpty()) return new DependencyDeclaration.None();
    if (matches.size() != 1) return new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies");
    try {
      return documents.get(matches.getFirst()).map(FactValues::dependencies)
          .orElseGet(() -> new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies"));
    } catch (RuntimeException invalid) {
      return new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies");
    }
  }

  @Override
  public SubtaskRoster subtaskRoster(String wpId) {
    List<String> matches = TasksFiles.names(documents.keySet(), wpId);
    if (matches.size() != 1) return new SubtaskRoster.Unresolvable();
    try {
      return documents.get(matches.getFirst()).map(FactValues::roster).orElseGet(SubtaskRoster.Unresolvable::new);
    } catch (RuntimeException invalid) {
      return new SubtaskRoster.Unresolvable();
    }
  }
}
