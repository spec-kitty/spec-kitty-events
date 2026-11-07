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
}
