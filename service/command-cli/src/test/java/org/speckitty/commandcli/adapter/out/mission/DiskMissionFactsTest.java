package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
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

  @Test
  void rosterRejectsEveryWpMetadataTypeFailureWithoutEscaping() throws Exception {
    for (String extra : List.of(
        "dependencies:\n",
        "work_package_title: 5\n",
        "description: =\n",
        "description: !!str 5\n",
        "description: &a !!str 5\n",
        "~: x\n",
        "null: x\n")) {
      write("WP01.md", task("[]").replace("title: test\n", "title: test\n" + extra));
      assertThat(facts().subtaskRoster("WP01")).as(extra).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
  }

  @Test
  void anchoredAndAliasedShorthandStringsRemainTaggedValues() throws Exception {
    write("WP01.md", task("[]").replace("title: test", "title: &a !!str test"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP01.md", task("[]").replace("title: test\n", "title: test\nagent: &a !!str x\ndescription: *a\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP02.md", "---\nwork_package_id: WP02\ndependencies: [&a !!str WP01]\nsubtasks: []\n---\n");
    assertThat(facts().dependencies("WP02")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
  }

  @Test
  void explicitScalarAndFlowCollectionTagsAreUnresolvableUnderR19() throws Exception {
    for (String declaration : List.of(
        "!!str foo", "!!null ''", "!!bool maybe", "! foo", "! '='", "!custom foo",
        "!<tag:yaml.org,2002:str> foo")) {
      write("WP01.md", task("[]").replace("title: test\n", "title: test\ndescription: " + declaration + "\n"));
      assertThat(facts().subtaskRoster("WP01")).as(declaration).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
    for (String declaration : List.of("!!seq [T001]", "!custom [T001]", "!!map {id: T001}")) {
      write("WP01.md", task(declaration));
      assertThat(facts().subtaskRoster("WP01")).as(declaration).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
    write("WP02.md", "---\nwork_package_id: WP02\ndependencies: ! WP01\n---\n");
    assertThat(facts().dependencies("WP02")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
  }

  @Test
  void explicitBlockCollectionTagsAnchorsAndAliasesAreUnresolvableUnderR19() throws Exception {
    write("WP01.md", task("!!seq\n  - T001"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP01.md", task("\n  - !!str T001\n  - !<tag:yaml.org,2002:str> T002"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP01.md", task("[]").replace("title: test\n", "title: test\ndescription: &a !!str 5\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP01.md", task("[]").replace("title: test\n", "title: test\nagent: &a !!str x\ndescription: *a\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
  }

  @Test
  void yamlAndTagDirectivesAreUnresolvableUnderR19() throws Exception {
    String header = "---\nwork_package_id: WP01\ntitle: test\nsubtasks: []\n";
    for (String directive : List.of("%YAML 1.2\n--- # document\n", "%TAG !e! tag:yaml.org,2002:\n--- # document\n")) {
      write("WP01.md", "---\n" + directive + header.substring(4) + "---\n");
      assertThat(facts().subtaskRoster("WP01")).as(directive).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
    write("WP01.md", "---\n%TAG !e! tag:yaml.org,2002:\n--- # document\n"
        + header.substring(4) + "description: !e!str 5\n---\n");
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
  }

  @Test
  void untaggedAnchorsAliasesCollectionsAndEqualsKeepTheirExistingRules() throws Exception {
    write("WP01.md", task("[=, &a T001, *a]"));
    SubtaskRoster.Resolved roster = (SubtaskRoster.Resolved) facts().subtaskRoster("WP01");
    assertThat(roster.ids()).containsExactly("=", "T001");
    write("WP01.md", task("[]").replace("title: test\n", "title: test\nagent: &a x\ndescription: *a\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Resolved.class);
    write("WP01.md", task("\n  - T001\n  - T002"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Resolved.class);
    write("WP02.md", "---\nwork_package_id: WP02\ndependencies: WP01\n---\n");
    assertThat(facts().dependencies("WP02")).isEqualTo(new DependencyDeclaration.Declared(List.of("WP01")));
    write("WP01.md", "---\n--- # document\nwork_package_id: WP01\ntitle: test\nsubtasks: []\n...\n---\n");
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Resolved.class);
  }

  @Test
  void shellPidUsesPythonIntCoercion() throws Exception {
    for (String declaration : List.of("' 12 '", "12.5", "true", "'99999999999'", "'1_000'", "'𝟙'")) {
      write("WP01.md", task("[]").replace("title: test\n", "title: test\nshell_pid: " + declaration + "\n"));
      assertThat(facts().subtaskRoster("WP01")).as(declaration).isInstanceOf(SubtaskRoster.Resolved.class);
    }
  }

  @Test
  void nonRegularMarkdownEntriesMakeRosterAndDependenciesUnresolvable() throws Exception {
    write("WP01.md", task("[]"));
    write("WP02.md", task("[]").replace("WP01", "WP02"));
    Files.createDirectory(mission.resolve("tasks/WP01-notes.md"));
    Files.createDirectory(mission.resolve("tasks/WP02-notes.md"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    assertThat(facts().dependencies("WP02")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
    Files.delete(mission.resolve("tasks/WP01-notes.md"));
    Files.delete(mission.resolve("tasks/WP02-notes.md"));
    Files.createSymbolicLink(mission.resolve("tasks/WP01-old.md"), Path.of("/nonexistent/wp01"));
    Files.createSymbolicLink(mission.resolve("tasks/WP02-old.md"), Path.of("/nonexistent/wp02"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    assertThat(facts().dependencies("WP02")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
  }

  @Test
  void existenceUsesCoreGlobSemanticsIncludingDanglingSymlinks() throws Exception {
    Files.createDirectories(mission.resolve("tasks"));
    Path dangling = Path.of("/nonexistent/wp04");
    Files.createSymbolicLink(mission.resolve("tasks/WP04-slug.md"), dangling);
    assertThat(TasksFiles.exists(mission, "WP04")).isTrue();
    Files.delete(mission.resolve("tasks/WP04-slug.md"));
    Files.createSymbolicLink(mission.resolve("tasks/WP04.md"), dangling);
    assertThat(TasksFiles.exists(mission, "WP04")).isFalse();
    Files.delete(mission.resolve("tasks/WP04.md"));
    Files.createDirectory(mission.resolve("tasks/WP04-slug.md"));
    assertThat(TasksFiles.exists(mission, "WP04")).as("fail closed where core crashes on a directory").isFalse();
  }

  @Test
  void aSingleNonRegularMatchFailsClosedWithoutOpeningIt() throws Exception {
    Files.createDirectories(mission.resolve("tasks"));
    Path fifo = mission.resolve("tasks/WP01.md");
    Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
    assertThat(mkfifo.waitFor()).isZero();
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    assertThat(facts().dependencies("WP01")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
  }

  @Test
  void wholeFileIsDecodedStrictlyBeforeTheFrontmatterCapApplies() throws Exception {
    Files.createDirectories(mission.resolve("tasks"));
    byte[] head = task("[]").getBytes(StandardCharsets.UTF_8);
    for (int offset : new int[] {65535, 65536}) {
      ByteArrayOutputStream document = new ByteArrayOutputStream();
      document.write(head);
      document.writeBytes(new byte[offset - head.length]);
      document.write("—".getBytes(StandardCharsets.UTF_8));
      document.writeBytes("\ntail\n".repeat(10).getBytes(StandardCharsets.UTF_8));
      Files.write(mission.resolve("tasks/WP01.md"), document.toByteArray());
      assertThat(facts().subtaskRoster("WP01")).as("multibyte offset " + offset)
          .isInstanceOf(SubtaskRoster.Resolved.class);
    }
    ByteArrayOutputStream invalid = new ByteArrayOutputStream();
    invalid.write(head);
    invalid.writeBytes(new byte[70000]);
    invalid.write(0xff);
    Files.write(mission.resolve("tasks/WP01.md"), invalid.toByteArray());
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
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
