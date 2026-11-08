package org.speckitty.commandcli.adapter.out.mission;

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
    List<Path> matches = TasksFiles.dependencies(mission, wpId);
    if (matches.isEmpty()) return new DependencyDeclaration.None();
    if (matches.size() != 1) return new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies");
    return Frontmatter.read(matches.getFirst()).map(FactValues::dependencies)
        .orElseGet(() -> new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies"));
  }

  @Override
  public SubtaskRoster subtaskRoster(String wpId) {
    List<Path> matches = TasksFiles.rosters(mission, wpId);
    if (matches.size() != 1) return new SubtaskRoster.Unresolvable();
    Optional<SubtaskRoster> roster = Frontmatter.read(matches.getFirst()).map(FactValues::roster);
    return roster.orElseGet(SubtaskRoster.Unresolvable::new);
  }
}
