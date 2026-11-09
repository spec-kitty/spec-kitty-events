package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.SubtaskRoster;

/** The value rules over parsed frontmatter, without the filesystem. */
class FactValuesTest {
  @Test
  void shellPidAcceptsOnlyTheR28Forms() {
    for (String pid : List.of("", "12", "' 12 '", "'+12'", "'-12'", "~", "'" + "1".repeat(4300) + "'")) {
      assertThat(roster("shell_pid: " + pid + "\n")).as(pid).isInstanceOf(SubtaskRoster.Resolved.class);
    }
    for (String pid : List.of("true", "12.5", "'1_000'", "'𝟙'", "'" + "1".repeat(4301) + "'", "'1 2'")) {
      assertThat(roster("shell_pid: " + pid + "\n")).as(pid).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
    assertThat(roster("")).as("absent").isInstanceOf(SubtaskRoster.Resolved.class);
  }

  @Test
  void rosterEntriesAreStringsOrIntegersOnlyUnderR25() {
    assertThat(roster("")).isEqualTo(new SubtaskRoster.Resolved(List.of("T001", "7")));
  }

  @Test
  void dependencyEntriesMustBeStrings() {
    Map<String, Object> frontmatter = Frontmatter.readText("WP02.md", "---\ndependencies: [WP01, 7]\n---\n").orElseThrow();
    assertThat(FactValues.dependencies(frontmatter)).isInstanceOf(DependencyDeclaration.Unresolvable.class);
  }

  private static SubtaskRoster roster(String extra) {
    String document = "---\nwork_package_id: WP01\ntitle: t\n" + extra + "subtasks: [T001, 7]\n---\n";
    return FactValues.roster(Frontmatter.readText("WP01.md", document).orElseThrow());
  }
}
