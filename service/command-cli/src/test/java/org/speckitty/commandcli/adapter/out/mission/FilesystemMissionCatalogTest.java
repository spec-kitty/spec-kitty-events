package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;

/** Root verification and fresh Mission/task discovery in real temporary git repositories. */
class FilesystemMissionCatalogTest {
  private static final String ID = "01J6XW9KQT7M0YB3N4R5CQZ2EX";

  @Test
  void rootRejectsBareAndLinkedWorktreeGitDirectories() throws Exception {
    Path bareRepository = Files.createTempDirectory("bare-host");
    Path bare = bareRepository.resolve(".git");
    run(bareRepository, "git", "init", "--bare", "--quiet", bare.toString());
    assertThat(RootCheckout.of(bare).root()).isEmpty();

    Path root = repo("linked-root");
    run(root, "git", "config", "user.email", "test@example.invalid");
    run(root, "git", "config", "user.name", "Test");
    run(root, "git", "commit", "--allow-empty", "-m", "initial");
    run(root, "git", "worktree", "add", "--quiet", "../linked-lane");
    assertThat(RootCheckout.of(root.resolve(".git/worktrees/linked-lane")).root()).isEmpty();
    assertThat(RootCheckout.of(root.resolve(".git")).root()).contains(root.toRealPath());
    delete(bareRepository);
    delete(root);
    delete(root.resolveSibling("linked-lane"));
  }

  @Test
  void everyGitBareBooleanSpellingIsRejectedCaseInsensitively() throws Exception {
    for (String value : new String[] {"TRUE", "yes", "On", "1", ""}) {
      Path host = Files.createTempDirectory("bare-config");
      Path bare = host.resolve(".git");
      run(host, "git", "init", "--bare", "--quiet", bare.toString());
      String config = Files.readString(bare.resolve("config"))
          .replace("bare = true", "bare" + (value.isEmpty() ? "" : " = " + value));
      Files.writeString(bare.resolve("config"), config.replace("[core]", "[CORE]"));
      assertThat(RootCheckout.of(bare).root()).as(value).isEmpty();
      delete(host);
    }
  }

  @Test
  void unreadableSiblingsAreSkippedAndBomPrefixedMetadataIsRead() throws Exception {
    Path root = repo("catalog-siblings");
    try {
      mission(root, "healthy", ID);
      Files.write(root.resolve("kitty-specs/healthy/meta.json"),
          ("﻿{\"mission_id\":\"" + ID + "\",\"coordination_branch\":\"  \"}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      Files.createDirectories(root.resolve("kitty-specs/broken"));
      Files.writeString(root.resolve("kitty-specs/broken/meta.json"), "{not json");
      FilesystemMissionCatalog catalog = new FilesystemMissionCatalog(root.resolve(".git"));
      assertThat(catalog.find(ID)).isInstanceOf(MissionCatalog.Found.class);
    } finally {
      delete(root);
    }
  }

  @Test
  void coordinationRoutedMissionsRefusePrimaryCheckoutAppends() throws Exception {
    Path root = repo("coordination-routed");
    try {
      Path mission = mission(root, "mission", ID);
      Files.writeString(mission.resolve("meta.json"),
          "{\"mission_id\":\"" + ID + "\",\"coordination_branch\":\"kitty/mission-status\"}");
      FilesystemMissionCatalog catalog = new FilesystemMissionCatalog(root.resolve(".git"));
      MissionRef ref = ((MissionCatalog.Found) catalog.find(ID)).mission();
      assertThat(ref.coordinationRouted()).isTrue();
      assertThat(catalog.locationMatches(ref, ".", "kitty-specs/mission", null)).isFalse();
    } finally {
      delete(root);
    }
  }

  @Test
  void duplicateMissionsAreAbsentAndACreatedMissionIsSeenWithoutRestart() throws Exception {
    Path root = repo("catalog");
    try {
      FilesystemMissionCatalog catalog = new FilesystemMissionCatalog(root.resolve(".git"));
      mission(root, "one", ID);
      mission(root, "two", ID);
      assertThat(catalog.find(ID)).isInstanceOf(MissionCatalog.NotFound.class);
      delete(root.resolve("kitty-specs/two"));
      assertThat(catalog.find(ID)).isInstanceOf(MissionCatalog.Found.class);
      mission(root, "later", "01J6XW9KQT7M0YB3N4R5CQZ2EZ");
      assertThat(catalog.find("01J6XW9KQT7M0YB3N4R5CQZ2EZ")).isInstanceOf(MissionCatalog.Found.class);
    } finally {
      delete(root);
    }
  }

  @Test
  void locationUsesRealPathsAndTaskLookupAcceptsOnlyCoreShapes() throws Exception {
    Path root = repo("location");
    Path outside = Files.createTempDirectory("outside-mission");
    try {
      Path mission = mission(root, "mission", ID);
      Files.writeString(mission.resolve("tasks/WP01.md"), "---\nsubtasks: []\n---\n");
      Files.writeString(mission.resolve("tasks/WP02-slice.md"), "---\nsubtasks: []\n---\n");
      Files.createSymbolicLink(root.resolve("kitty-specs/escape"), outside);
      FilesystemMissionCatalog catalog = new FilesystemMissionCatalog(root.resolve(".git"));
      String escapedId = "01J6XW9KQT7M0YB3N4R5CQZ2EZ";
      Files.writeString(outside.resolve("meta.json"), "{\"mission_id\":\"" + escapedId + "\"}");
      MissionRef escaped = ((MissionCatalog.Found) catalog.find(escapedId)).mission();
      assertThat(catalog.locationMatches(escaped, ".", "kitty-specs/escape", null)).isFalse();
      MissionRef ref = ((MissionCatalog.Found) catalog.find(ID)).mission();
      assertThat(catalog.locationMatches(ref, ".", "kitty-specs/mission", null)).isTrue();
      assertThat(catalog.locationMatches(ref, "lane", "kitty-specs/mission", null)).isFalse();
      assertThat(catalog.locationMatches(ref, ".", "kitty-specs/mission", "refs/heads/x")).isFalse();
      assertThat(catalog.locationMatches(ref, ".", "kitty-specs/escape", null)).isFalse();
      assertThat(catalog.wpExists(ref, "WP01")).isTrue();
      assertThat(catalog.wpExists(ref, "WP02")).isTrue();
      assertThat(catalog.wpExists(ref, "WP03")).isFalse();
    } finally {
      delete(root);
      delete(outside);
    }
  }

  private static Path repo(String name) throws Exception {
    Path root = Files.createTempDirectory(name);
    run(root, "git", "init", "--quiet");
    return root;
  }

  private static Path mission(Path root, String directory, String id) throws Exception {
    Path mission = root.resolve("kitty-specs").resolve(directory);
    Files.createDirectories(mission.resolve("tasks"));
    Files.writeString(mission.resolve("meta.json"), "{\"mission_id\":\"" + id + "\"}");
    return mission;
  }

  private static void run(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new AssertionError(String.join(" ", command) + ": " + new String(process.getInputStream().readAllBytes()));
    }
  }

  private static void delete(Path root) throws Exception {
    if (!Files.exists(root)) return;
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }
}
