package org.speckitty.commandcli.application.write.port;

import org.speckitty.commandcli.domain.authority.MissionFacts;

/** Resolves Mission and work-package facts without exposing host paths to the application. */
public interface MissionCatalog {
  sealed interface FindResult permits Found, NotFound, RootUnverifiable {}
  record Found(MissionRef mission) implements FindResult {}
  record NotFound() implements FindResult {}
  record RootUnverifiable() implements FindResult {}

  FindResult find(String missionId);

  boolean locationMatches(MissionRef mission, String checkout, String missionDirectory, String destinationRef);

  boolean wpExists(MissionRef mission, String wpId);

  /** Read while the Mission lock is held. */
  MissionFacts facts(MissionRef mission);
}
