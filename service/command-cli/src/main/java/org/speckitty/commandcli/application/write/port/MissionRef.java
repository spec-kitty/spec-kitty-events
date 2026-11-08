package org.speckitty.commandcli.application.write.port;

/** The Mission directory verified by the catalog, relative to the repository root checkout. */
public record MissionRef(String missionId, String directory) {}
