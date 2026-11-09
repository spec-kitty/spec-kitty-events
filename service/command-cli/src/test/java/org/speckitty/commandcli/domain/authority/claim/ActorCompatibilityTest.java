package org.speckitty.commandcli.domain.authority.claim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ActorCompatibilityTest {
  @Test
  void projectsStructuredCompactAndBareActorsToTheBareTool() {
    assertThat(ActorCompatibility.key(Map.of("tool", "codex", "role", "reviewer"))).isEqualTo("codex");
    assertThat(ActorCompatibility.key("codex:kimi-k3:python-pedro:implementer")).isEqualTo("codex");
    assertThat(ActorCompatibility.key(" codex ")).isEqualTo("codex");
    assertThat(ActorCompatibility.key(" ")).isNull();
    assertThat(ActorCompatibility.key(null)).isNull();
    assertThat(ActorCompatibility.key(42)).isEqualTo("42");
    assertThat(ActorCompatibility.key(":missing-tool")).isEqualTo(":missing-tool");
  }

  @Test
  void missingActorsAndMatchingToolsAreCompatible() {
    assertThat(ActorCompatibility.compatible(null, "codex", false)).isTrue();
    assertThat(ActorCompatibility.compatible("codex", null, false)).isTrue();
    assertThat(ActorCompatibility.compatible("codex:kimi:pedro:implementer", Map.of("tool", "codex"), false)).isTrue();
    assertThat(ActorCompatibility.compatible("codex", "claude", false)).isFalse();
  }

  @Test
  void onlyGenericExistingPlaceholdersYield() {
    for (String generic : new String[] {"implement-command", "unknown", "user"}) {
      assertThat(ActorCompatibility.compatible(generic, "codex", true)).as(generic).isTrue();
      assertThat(ActorCompatibility.compatible(generic, "codex", false)).as(generic).isFalse();
    }
    assertThat(ActorCompatibility.compatible("codex", "unknown", true)).isFalse();
    assertThat(ActorCompatibility.compatible("real-agent", "codex", true)).isFalse();
  }
}
