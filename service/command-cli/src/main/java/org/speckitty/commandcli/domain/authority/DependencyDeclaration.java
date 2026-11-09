package org.speckitty.commandcli.domain.authority;

import java.util.List;

/** Dependency declarations read from a work package's authored frontmatter. */
public sealed interface DependencyDeclaration permits DependencyDeclaration.Declared, DependencyDeclaration.None, DependencyDeclaration.Unresolvable {
  record Declared(List<String> wpIds) implements DependencyDeclaration {
    public Declared {
      wpIds = List.copyOf(wpIds);
    }
  }

  record None() implements DependencyDeclaration {}

  record Unresolvable(String message) implements DependencyDeclaration {}
}
