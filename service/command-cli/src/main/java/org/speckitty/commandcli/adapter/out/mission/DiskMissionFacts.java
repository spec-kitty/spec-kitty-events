package org.speckitty.commandcli.adapter.out.mission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.authority.SubtaskRoster;

/** Production MissionFacts read from task frontmatter while the Mission lock is held. */
public final class DiskMissionFacts implements MissionFacts {
  private final Path mission;

  public DiskMissionFacts(Path mission) {
    this.mission = mission;
  }

  @Override
  public DependencyDeclaration dependencies(String wpId) {
    try {
      List<Path> matches = TasksFiles.paths(mission, wpId);
      if (matches.isEmpty()) return new DependencyDeclaration.None();
      if (matches.size() != 1 || !Files.isRegularFile(matches.getFirst())) {
        return new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies");
      }
      return Frontmatter.read(matches.getFirst()).map(FactValues::dependencies)
          .orElseGet(() -> new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies"));
    } catch (RuntimeException invalid) {
      return new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies");
    }
  }

  @Override
  public SubtaskRoster subtaskRoster(String wpId) {
    try {
      List<Path> matches = TasksFiles.paths(mission, wpId);
      if (matches.size() != 1 || !Files.isRegularFile(matches.getFirst())) {
        return new SubtaskRoster.Unresolvable();
      }
      Optional<SubtaskRoster> roster = Frontmatter.read(matches.getFirst()).map(FactValues::roster);
      return roster.orElseGet(SubtaskRoster.Unresolvable::new);
    } catch (RuntimeException invalid) {
      return new SubtaskRoster.Unresolvable();
    }
  }
}
