package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
  void rosterTrimsAndDeduplicatesStringAndIntegerIds() throws Exception {
    write("WP01.md", task("[1, ' T001 ', T001, on, '', '  ']"));
    SubtaskRoster.Resolved roster = (SubtaskRoster.Resolved) facts().subtaskRoster("WP01");
    assertThat(roster.ids()).containsExactly("1", "T001", "on");
  }

  @Test
  void aRosterEntryThatIsNotAStringOrIntegerIsUnresolvableUnderR25() throws Exception {
    for (String entry : List.of("1.1", "1.10", "true", "null", "~", "{id: T001}", "[T001]", "2001-12-14",
        "2001-12-14 21:59:43", ".inf", "1e3")) {
      write("WP01.md", task("[T001, " + entry + "]"));
      assertThat(facts().subtaskRoster("WP01")).as(entry).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
  }

  @Test
  void bothLocatorsMatchTheWorkPackageIdCaseInsensitivelyUnderR26() throws Exception {
    write("wp01-lowercase.md", task("[]"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Resolved.class);
    write("wp02-lowercase.md", "---\nwork_package_id: WP02\ndependencies: [WP01]\n---\n");
    assertThat(facts().dependencies("WP02")).isEqualTo(new DependencyDeclaration.Declared(List.of("WP01")));
    write("WP02-other.md", "---\ndependencies: []\n---\n");
    assertThat(facts().dependencies("WP02")).as("a second spelling is ambiguous, never hidden")
        .isInstanceOf(DependencyDeclaration.Unresolvable.class);
  }

  @Test
  void dependenciesMatchCoreFrontmatterAndFilenameRules() throws Exception {
    String text = "﻿" + "---\nwork_package_id: WP02\ndependencies: [WP01]\nsubtasks: []\n---\n"
        .replace("\n", "\r").replace("---\r", "---  \r");
    write("WP02.md", text);
    assertThat(facts().dependencies("WP02")).isEqualTo(new DependencyDeclaration.Declared(List.of("WP01")));
    write("WP02.md.orig", "---\ndependencies: []\n---\n");
    if (caseSensitiveFilesystem()) {
      // Core's glob("*.md") is case-sensitive on such a filesystem, so a second file with an upper-case extension is not a match. On a
      // case-insensitive one (default APFS, NTFS) "WP02.MD" is the same file as "WP02.md", and writing it replaces it.
      write("WP02.MD", "---\ndependencies: []\n---\n");
    }
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
  void anchorsAliasesDocumentEndsAndFurtherDocumentsAreUnresolvableUnderR20() throws Exception {
    write("WP01.md", task("[]").replace("title: test", "title: &a test"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP01.md", task("[]").replace("title: test\n", "title: test\nagent: &a x\ndescription: *a\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP01.md", task("[]").replace("title: test\n", "title: test\ndescription: &a: x\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP01.md", task("[]").replace("title: test\n", "title: test\n...\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP01.md", task("[]").replace("title: test\n", "title: test\n---\nother: document\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
    write("WP02.md", "---\nwork_package_id: WP02\ndependencies: [&a WP01]\nsubtasks: []\n---\n");
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
  void loneAndTrailingDirectivesAreUnresolvableUnderR20() throws Exception {
    for (String directive : List.of("%YAML 1.2", "%TAG !e! tag:yaml.org,2002:")) {
      write("WP02.md", "---\n" + directive + "\n---\n");
      assertThat(facts().dependencies("WP02")).as(directive)
          .isInstanceOf(DependencyDeclaration.Unresolvable.class);
    }
    write("WP01.md", task("[]").replace("title: test\n", "title: test\n...\n%YAML 1.2\n"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Unresolvable.class);
  }

  @Test
  void yaml11WhitespaceIsUnresolvableUnderR20() throws Exception {
    for (String declaration : List.of(
        "description: line one\tcontinued\n",
        "description\t: value\n",
        "agent: x\u2028description: y\n",
        "description: left\u2028right\n",
        "description: left\u0085right\n",
        "description: left\u2029right\n")) {
      write("WP01.md", task("[]").replace("title: test\n", "title: test\n" + declaration));
      assertThat(facts().subtaskRoster("WP01")).as(declaration)
          .isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
  }

  @Test
  void aPlainEqualsSignIsUnresolvableEverywhereUnderR27() throws Exception {
    for (String declaration : List.of("[=, T001]", "[T001, =]", "\n  - T001\n  - =")) {
      write("WP01.md", task(declaration));
      assertThat(facts().subtaskRoster("WP01")).as(declaration).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
    for (String extra : List.of("description: =\n", "agent: =\n", "history: [=]\n", "history: [{a: =}]\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isEmpty();
    }
    for (String extra : List.of("description: '='\n", "description: \"=\"\n", "description: a=b\n", "description: ==\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isPresent();
    }
    write("WP01.md", task("\n  - T001\n  - T002"));
    assertThat(facts().subtaskRoster("WP01")).isInstanceOf(SubtaskRoster.Resolved.class);
    write("WP02.md", "---\nwork_package_id: WP02\ndependencies: WP01\n---\n");
    assertThat(facts().dependencies("WP02")).isEqualTo(new DependencyDeclaration.Declared(List.of("WP01")));
  }

  @Test
  void everyMappingKeyMustBeAStringUnderR22() {
    for (String extra : List.of(
        "history: [{1: x, 1.0: y}]\n",
        "history: [{true: x, 1: y}]\n",
        "history: [{1: x, 2.0: y}]\n",
        "history: [{true: x, false: y}]\n",
        "history: [{0: x, -0.0: y}]\n",
        "history: [{.nan: x}]\n",
        "history: [{.inf: x}]\n",
        "history: [{~: x}]\n",
        "history: [{null: x}]\n",
        "history: [{0o10: x}]\n",
        "history: [{0x10: x}]\n",
        "history: [{2001-12-14: x}]\n",
        "history: [{=: x}]\n",
        "history:\n  - ? [1]\n    : x\n",
        "history:\n  - ? {a: 1}\n    : x\n",
        "history:\n  - ? {a: 1, b: 2}\n    : x\n    ? {b: 2, a: 1}\n    : y\n",
        "history:\n  - ? .nan\n    : x\n    ? .nan\n    : y\n",
        "history:\n  - ? [.nan]\n    : x\n",
        "history:\n  - ? 2001-12-14t21:59:43.10-05:00\n    : x\n    ? 2001-12-15 2:59:43.10\n    : y\n",
        "history:\n  - ? =\n    : x\n    ? '='\n    : y\n",
        "? 1\n: x\n",
        "? [a]\n: x\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isEmpty();
    }
    for (String extra : List.of(
        "history: [{a: 1, b: 2.0}]\n",
        "history: [{'1': x, \"2.0\": y, 'true': z, '=': w}]\n",
        "history:\n  - 1:20: x\n  - 010a: y\n  - yes: z\n  - on: w\n",
        "history: [{'.nan': x, '~': y, 'null': z, '<<': w}]\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isPresent();
    }
  }

  @Test
  void duplicateStringKeysAreRefusedWhateverTheirQuoting() {
    for (String extra : List.of("agent: a\nagent: b\n", "agent: a\n\"agent\": b\n", "agent: a\n'agent': b\n",
        "history: [{a: 1, 'a': 2}]\n", "history: [{\"\\x61\": 1, a: 2}]\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isEmpty();
    }
  }

  @Test
  void duplicatePlainMergeKeysAreUnresolvableButOneMergeStillReads() {
    for (String extra : List.of(
        "<<: {agent: x}\n<<: {description: y}\n",
        "<<: {agent: x}\n? <<\n: {description: y}\n",
        "history: [{<<: {a: b}, <<: {c: d}}]\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isEmpty();
    }
    assertThat(Frontmatter.readText("WP01.md", taskWith("<<: {agent: x}\n"))).isPresent();
    assertThat(Frontmatter.readText("WP01.md", taskWith("'<<': {agent: x}\n'<<': {description: y}\n")))
        .as("quoted duplicates remain ordinary duplicate keys and are also refused")
        .isEmpty();
  }

  @Test
  void flowValueColonsRequireWhitespaceUnderR21() {
    for (String extra : List.of(
        "history: ['a':b]\n",
        "history: [\"a\":b]\n",
        "history: [{a:[b]}]\n",
        "history: [{a:{b: c}}]\n",
        "history: [a:]\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isEmpty();
    }
    for (String extra : List.of(
        "history: ['a': b]\n",
        "history: [{a: [b]}]\n",
        "history: [{a:\n  b}]\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isPresent();
    }
  }

  @Test
  void aSpacesOnlyFirstBlockScalarLineMakesItUnresolvableUnderR23() {
    for (String extra : List.of(
        "description: |\n \n  x\n",
        "description: >-\n \n  x\n",
        "description: |\n \n x\n",
        "description: |\n   \n  x\n",
        "description: |\n \n \nagent: x\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isEmpty();
    }
    for (String extra : List.of(
        "description: |\n\n \n  x\n",
        "description: |\n\n\n  x\n",
        "description: |2\n \n  x\n",
        "description: |\n  x\n \n  y\n",
        "description: >-\n  first\n  second\n")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith(extra))).as(extra).isPresent();
    }
  }

  @Test
  void shellPidIsAnIntegerOrAnAsciiDigitStringUnderR28() throws Exception {
    for (String declaration : List.of("' 12 '", "12", "~", "null", "''", "'99999999999'", "'+7'", "'-7'", "\"\\t12\\n\"",
        "'" + "9".repeat(4300) + "'", "0x1f", "-3")) {
      write("WP01.md", task("[]").replace("title: test\n", "title: test\nshell_pid: " + declaration + "\n"));
      assertThat(facts().subtaskRoster("WP01")).as(declaration).isInstanceOf(SubtaskRoster.Resolved.class);
    }
    for (String declaration : List.of("12.5", "1e3", "true", "false", "'1_000'", "'𝟙'", "'１２'", "'٣'", "' '",
        "'1.0'", "'0x10'", "'12abc'", "'1\u00a0'", "'++1'", "'+'", "[12]", "{a: 1}", "2001-12-14",
        "'" + "9".repeat(4301) + "'")) {
      write("WP01.md", task("[]").replace("title: test\n", "title: test\nshell_pid: " + declaration + "\n"));
      assertThat(facts().subtaskRoster("WP01")).as(declaration).isInstanceOf(SubtaskRoster.Unresolvable.class);
    }
  }

  @Test
  void aDecimalIntegerOfMoreThan4300DigitsMakesTheFileUnreadableUnderR29() throws Exception {
    for (String number : List.of("9".repeat(4301), "-" + "9".repeat(4301), "0".repeat(4300) + "1", "+" + "1".repeat(4301))) {
      write("WP01.md", task("[]").replace("title: test\n", "title: test\nagent: " + number + "\n"));
      assertThat(facts().subtaskRoster("WP01")).as(number.length() + " digits").isInstanceOf(SubtaskRoster.Unresolvable.class);
      write("WP02.md", "---\nwork_package_id: WP02\ndependencies: [WP01]\nagent: " + number + "\n---\n");
      assertThat(facts().dependencies("WP02")).as(number.length() + " digits")
          .isInstanceOf(DependencyDeclaration.Unresolvable.class);
    }
    for (String number : List.of("9".repeat(4300), "-" + "9".repeat(4300), "1." + "9".repeat(5000))) {
      write("WP01.md", task("[]").replace("title: test\n", "title: test\nagent: " + number + "\n"));
      assertThat(facts().subtaskRoster("WP01")).as(number.length() + " chars").isInstanceOf(SubtaskRoster.Resolved.class);
    }
    assertThat(Frontmatter.readText("WP01.md", taskWith("agent: '" + "9".repeat(5000) + "'\n"))).as("quoted").isPresent();
  }

  @Test
  void anIntegerOfAnyBaseOver4300DecimalDigitsMakesTheFileUnreadableAnywhereUnderR29() throws Exception {
    BigInteger limit = BigInteger.TEN.pow(4300);
    BigInteger largest = limit.subtract(BigInteger.ONE);
    List<String> over = List.of("0x" + limit.toString(16), "0x" + limit.multiply(BigInteger.valueOf(7)).toString(16),
        "0o" + limit.toString(8), "0x" + "f".repeat(5000), "0o" + "7".repeat(5000));
    List<String> within = List.of("0x" + largest.toString(16), "0o" + largest.toString(8), "0x" + "0".repeat(5000) + "1",
        "0o" + "0".repeat(5000) + "7", "0x0");
    List<String> places = List.of("phase: %s", "agent: %s", "history: [%s]", "history: {a: %s}", "history: [{a: [%s]}]",
        "history: {a: {b: %s}}", "\"history\": [%s]");
    for (String place : places) {
      for (String number : over) {
        String line = place.formatted(number);
        write("WP01.md", task("[]").replace("title: test\n", "title: test\n" + line + "\n"));
        assertThat(facts().subtaskRoster("WP01")).as(line.substring(0, Math.min(40, line.length()))).isInstanceOf(SubtaskRoster.Unresolvable.class);
        write("WP02.md", "---\nwork_package_id: WP02\ndependencies: [WP01]\n" + line + "\n---\n");
        assertThat(facts().dependencies("WP02")).as(line.substring(0, Math.min(40, line.length())))
            .isInstanceOf(DependencyDeclaration.Unresolvable.class);
      }
      for (String number : within) {
        String line = place.formatted(number);
        assertThat(Frontmatter.readText("WP01.md", taskWith(line + "\n"))).as(line.substring(0, Math.min(40, line.length()))).isPresent();
      }
    }
  }

  @Test
  void aRosterIdWhoseDecimalRenderingExceeds4300DigitsIsUnresolvable() throws Exception {
    for (String entry : List.of("0x" + "f".repeat(4000), "0o" + "7".repeat(4800), "0x" + "f".repeat(3000))) {
      write("WP01.md", task("[" + entry + "]"));
      boolean resolved = entry.length() < 4000;
      assertThat(facts().subtaskRoster("WP01")).as(entry.length() + " chars")
          .isInstanceOf(resolved ? SubtaskRoster.Resolved.class : SubtaskRoster.Unresolvable.class);
    }
  }

  @Test
  void aTimestampWithMoreThanSixFractionalDigitsMakesTheFileUnreadableUnderR30() throws Exception {
    for (String timestamp : List.of("9999-12-31 23:59:59.9999995", "2001-01-01 00:00:00.1234567", "2001-01-01T00:00:00.0000000Z",
        "2001-01-01 00:00:00.1234567 +01:00", "2001-01-01 00:00:00." + "9".repeat(40))) {
      write("WP02.md", "---\nwork_package_id: WP02\ndependencies: [WP01]\nagent: " + timestamp + "\n---\n");
      assertThat(facts().dependencies("WP02")).as(timestamp).isInstanceOf(DependencyDeclaration.Unresolvable.class);
      assertThat(Frontmatter.readText("WP01.md", taskWith("history: [" + timestamp + "]\n"))).as(timestamp).isEmpty();
    }
    for (String timestamp : List.of("9999-12-31 23:59:59.999999", "2001-01-01 00:00:00.123456", "2001-01-01 00:00:00.",
        "2001-01-01 00:00:00.5", "2001-01-01T00:00:00.000000Z", "2001-01-01 00:00:00")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith("history: [" + timestamp + "]\n"))).as(timestamp).isPresent();
    }
  }

  @Test
  void plainScalarsResolveByTheYaml12CoreSchemaNotSnakeYamls11Rules() throws Exception {
    write("WP01.md", task("[1:20, 010, 0o10, 0x1F, yes, No, on, y, 190:20:30, 'true', 007]"));
    SubtaskRoster.Resolved roster = (SubtaskRoster.Resolved) facts().subtaskRoster("WP01");
    assertThat(roster.ids()).containsExactly("1:20", "10", "8", "31", "yes", "No", "on", "y", "190:20:30", "true", "7");
    write("WP02.md", "---\nwork_package_id: WP02\ndependencies: [1:20, yes]\n---\n");
    assertThat(facts().dependencies("WP02")).isEqualTo(new DependencyDeclaration.Declared(List.of("1:20", "yes")));
    for (String declaration : List.of("[010]", "[0o10]", "[true]", "[.5]", "[2001-12-14]")) {
      write("WP02.md", "---\nwork_package_id: WP02\ndependencies: " + declaration + "\n---\n");
      assertThat(facts().dependencies("WP02")).as(declaration).isInstanceOf(DependencyDeclaration.Unresolvable.class);
    }
  }

  @Test
  void numericSpellingsOnlyRuamelReadsAsNumbersAreUnresolvable() {
    for (String scalar : List.of("1_000", "1_", "0_7", "+0x1", "-0o7", "0b1", "-0b1", "1.5_5", "+_1", "0x_f", "1__0")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith("agent: " + scalar + "\n"))).as(scalar).isEmpty();
      assertThat(Frontmatter.readText("WP01.md", taskWith("agent: '" + scalar + "'\n"))).as("quoted " + scalar).isPresent();
    }
  }

  @Test
  void invalidTimestampsMakeTheFileUnreadableAsInCore() throws Exception {
    for (String scalar : List.of("2001-13-45", "2001-02-30", "0000-01-01", "2001-12-14T24:00:00", "2001-12-14T23:60:00",
        "2001-12-14T23:59:60", "2001-12-14T23:59:59+25:00", "1999-02-29", "2001-12-14T23:59:59.9999995Z")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith("created_at: " + scalar + "\n"))).as(scalar).isEmpty();
    }
    for (String scalar : List.of("2001-12-14", "2001-12-14t21:59:43.10-05:00", "2001-12-15 2:59:43.10", "2000-02-29",
        "2001-12-14T23:59:59.999999Z", "2001-12-14T23:59:59+23:59")) {
      assertThat(Frontmatter.readText("WP01.md", taskWith("created_at: " + scalar + "\n"))).as(scalar).isPresent();
    }
    write("WP01.md", taskWith("created_at: 2001-12-14\n"));
    assertThat(facts().subtaskRoster("WP01")).as("a date where core wants a string").isInstanceOf(SubtaskRoster.Unresolvable.class);
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
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    boolean created;
    try {
      created = new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0
          && Files.exists(fifo, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(fifo, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException noMkfifo) {
      created = false;
    }
    // Where mkfifo is the platform's (Linux, macOS) the fixture must exist, or the test fails rather than skips. On Windows a FIFO
    // cannot be a directory entry the JVM sees (the MSYS mkfifo leaves no non-regular file under tasks/), so there is nothing to
    // classify: production correctly finds no match (None / Unresolvable roster).
    if (posix) {
      assertThat(created).as("mkfifo must create a non-regular entry").isTrue();
    } else {
      assumeTrue(created, "a FIFO cannot exist as a non-regular directory entry on this platform");
    }
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

  private String taskWith(String extra) {
    return task("[]").replace("title: test\n", "title: test\n" + extra);
  }

  /** Probes the filesystem under the temp directory: is the upper-case alias of a freshly created file a different file? */
  private boolean caseSensitiveFilesystem() throws Exception {
    Path probe = Files.createTempFile(mission, "case-probe-", ".tmp");
    try {
      Path alias = probe.resolveSibling(probe.getFileName().toString().toUpperCase(Locale.ROOT));
      return !Files.exists(alias);
    } finally {
      Files.delete(probe);
    }
  }

  private void write(String name, String text) throws Exception {
    Path path = mission.resolve("tasks").resolve(name);
    Files.createDirectories(path.getParent());
    Files.write(path, text.getBytes(StandardCharsets.UTF_8));
  }
}
