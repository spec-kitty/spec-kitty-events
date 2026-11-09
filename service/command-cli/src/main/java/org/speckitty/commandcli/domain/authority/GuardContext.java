package org.speckitty.commandcli.domain.authority;

/** The ten inputs core's lane guards consult; tri-state booleans deliberately permit no verdict. */
public record GuardContext(
    String actor,
    String workspaceContext,
    Boolean subtasksComplete,
    Boolean implementationEvidencePresent,
    String reason,
    String reviewRef,
    Object evidence,
    boolean force,
    Object reviewResult,
    Boolean dependencyReady) {}
