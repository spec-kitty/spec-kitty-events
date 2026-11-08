package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.SubtaskRoster;

class DiskMissionFactsTest {
  @TempDir Path mission;

  @Test
  void rosterRequiresOneCoreShapedFileAndValidWpMetadata() throws Exception {
    write("WP01.md", "---\nwork_package_id: WP01\ndependencies: []\n---\n");
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    for (String document : List.of(
        "---\nwork_package_id: WP01\nbogus_key: 1\nsubtasks: []\n---\n",
        "---\ntitle: missing id\nsubtasks: []\n---\n",
        "---\nwork_package_id: WP01\nsubtasks: null\n---\n",
        "---\nwork_package_id: WP01\nsubtasks: T001\n---\n",
        "---\nwork_package_id: WP01\nsubtasks: []\nsubtasks: []\n---\n")) {
      write("WP01.md", document);
      assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
    write("WP01.md", task("[T001]"));
    write("WP01-extra.md", task("[]"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    Files.delete(mission.resolve("tasks/WP01-extra.md"));
    write("WP01.md.orig", task("[]"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Resolved.class);
  }

  @Test
  void rosterCoercesTrimsAndDeduplicatesIdsLikePython() throws Exception {
    write("WP01.md", task("[1, 1.10, ' T001 ', T001, on, true, null]"));
    SubtaskRoster.Resolved roster = (SubtaskRoster.Resolved) facts().subtaskRoster("WP01");
    assertThat(roster.ids()).containsExactly("1", "1.1", "T001", "on", "True", "None");
    Files.delete(mission.resolve("tasks/WP01.md"));
    write("wp01-lowercase.md", task("[]"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Resolved.class);
  }

  @Test
  void dependenciesMatchCoreFrontmatterAndFilenameRules() throws Exception {
    String text = "﻿" + "---\nwork_package_id: WP02\ndependencies: [WP01]\nsubtasks: []\n---\n"
        .replace("\n", "\r").replace("---\r", "---  \r");
    write("WP02.md", text);
    assertThat(facts().dependencies("WP02")).isEqualTo(new DependencyDeclaration.Declared(List.of("WP01")));
    write("WP02.md.orig", "---\ndependencies: []\n---\n");
    write("WP02.MD", "---\ndependencies: []\n---\n");
    assertThat(facts().dependencies("WP02")).isEqualTo(new DependencyDeclaration.Declared(List.of("WP01")));
    write("WP02.md", "---\ndependencies: [WP01]\ndependencies: []\n---\n");
    assertThat(facts().dependencies("WP02")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
    write("WP02.md", "---\r---\r");
    assertThat(facts().dependencies("WP02")).isInstanceOf(DependencyDeclaration.None.class);
  }

  @Test
  void dependencyIdsUsePythonWhitespaceIncludingNbsp() throws Exception {
    write("WP02.md", "---\ndependencies: [\" WP01　\"]\n---\n");
    assertThat(facts().dependencies("WP02")).isEqualTo(new DependencyDeclaration.Declared(List.of("WP01")));
  }

  private DiskMissionFacts facts() {
    return new DiskMissionFacts(mission);
  }

  private String task(String subtasks) {
    return "---\nwork_package_id: WP01\ntitle: test\nsubtasks: " + subtasks + "\n---\n";
  }

  private void write(String name, String text) throws Exception {
    Path path = mission.resolve("tasks").resolve(name);
    Files.createDirectories(path.getParent());
    Files.write(path, text.getBytes(StandardCharsets.UTF_8));
  }
}
