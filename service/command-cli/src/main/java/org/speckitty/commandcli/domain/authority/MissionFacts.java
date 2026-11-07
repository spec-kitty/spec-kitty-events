package org.speckitty.commandcli.domain.authority;

import java.util.List;

/** Authored mission facts needed by the pure authority. */
public interface MissionFacts {
  DependencyDeclaration dependencies(String wpId);

  List<String> subtaskRoster(String wpId);
}
