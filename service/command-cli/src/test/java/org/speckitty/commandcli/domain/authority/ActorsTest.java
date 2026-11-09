package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ActorsTest {
  @Test
  void projectsStructuredToolThenRoleWithoutSplittingCompactActors() {
    assertThat(Actors.project(Map.of("tool", "codex", "role", "reviewer"))).isEqualTo("codex");
    assertThat(Actors.project(Map.of("tool", "", "role", "reviewer"))).isEqualTo("reviewer");
    assertThat(Actors.project(Map.of())).isEmpty();
    assertThat(Actors.project("codex:model:profile:implementer")).isEqualTo("codex:model:profile:implementer");
    assertThat(Actors.isMigration("migration:rebuild")).isTrue();
    assertThat(Actors.isMigration(Map.of("tool", "migration:rebuild"))).isTrue();
    assertThat(Actors.isMigration("codex:migration:x")).isFalse();
    assertThat(Actors.isMigration(null)).isFalse();
  }

  @Test
  void aFiveKeyActorIsReadFromALogButNeverWritten() {
    Map<String, Object> five = new java.util.LinkedHashMap<>();
    five.put("role", "r");
    five.put("profile", "p");
    five.put("tool", "t");
    five.put("model", null);
    five.put("profile_source", "flag");
    assertThat(Actors.decodable(five)).as("the CLI's own reader accepts it ahead of the events floor").isTrue();
    assertThat(Actors.writable(five)).as("core: no writer may emit it until #6721").isFalse();
    Map<String, Object> four = new java.util.LinkedHashMap<>(five);
    four.remove("profile_source");
    assertThat(Actors.writable(four)).isTrue();
    assertThat(Actors.writable("codex")).isTrue();
    assertThat(Actors.writable(null)).isTrue();
    assertThat(Actors.writable(Map.of("tool", "x"))).isFalse();
    assertThat(Actors.writable(Map.of("profile_source", "flag"))).isFalse();
  }
}
