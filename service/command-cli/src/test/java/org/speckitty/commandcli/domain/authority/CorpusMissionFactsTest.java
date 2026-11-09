package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CorpusMissionFactsTest {
  @Test
  void acceptedLegacyFormsMatchCore() {
    assertThat(dependencies("dependencies: \"[]\"")).isInstanceOf(DependencyDeclaration.None.class);
    assertThat(dependencies("dependencies: \"\"")).isInstanceOf(DependencyDeclaration.None.class);
    assertThat(dependencies("dependencies: []")).isInstanceOf(DependencyDeclaration.None.class);
    assertThat(dependencies("dependencies: WP01")).isEqualTo(new DependencyDeclaration.Declared(java.util.List.of("WP01")));
    assertThat(dependencies("dependencies: \"WP01, WP02\"")).isEqualTo(new DependencyDeclaration.Declared(java.util.List.of("WP01", "WP02")));
    assertThat(dependencies("dependencies: [WP01, WP02]")).isEqualTo(new DependencyDeclaration.Declared(java.util.List.of("WP01", "WP02")));
  }

  @Test
  void ambiguousUnreadableAndMalformedDeclarationsAreUnresolvable() {
    String task = task("WP02", "dependencies: [WP01]");
    CorpusMissionFacts ambiguous = new CorpusMissionFacts(Map.of("WP02.md", task, "WP02-renamed.md", task));
    assertThat(ambiguous.dependencies("WP02")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
    assertThat(dependencies("dependencies: 42")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
    assertThat(dependencies("dependencies: [WP01, 42]")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
    CorpusMissionFacts unreadable = new CorpusMissionFacts(Map.of("WP02.md", "not frontmatter"));
    assertThat(unreadable.dependencies("WP02")).isInstanceOf(DependencyDeclaration.Unresolvable.class);
    assertThat(new CorpusMissionFacts(Map.of()).dependencies("WP02")).isInstanceOf(DependencyDeclaration.None.class);
  }

  private static DependencyDeclaration dependencies(String declaration) {
    return new CorpusMissionFacts(Map.of("WP02.md", task("WP02", declaration))).dependencies("WP02");
  }

  private static String task(String wpId, String declaration) {
    return "---\nwork_package_id: " + wpId + "\n" + declaration + "\nsubtasks: []\n---\n# WP\n";
  }
}
