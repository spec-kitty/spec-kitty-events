package org.speckitty.commandcli.domain.authority;

/** Authored mission facts needed by the pure authority. */
public interface MissionFacts {
  DependencyDeclaration dependencies(String wpId);

  SubtaskRoster subtaskRoster(String wpId);
}
