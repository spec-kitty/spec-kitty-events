package org.speckitty.commandcli.application.write;

import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.domain.service.RefusalCode;

/**
 * Resolves the Mission a request names and verifies the location the request claims for it: the surface check, the Mission lookup and
 * the checkout, directory and destination check, in that order. The write and the status-log repair share it, so the two refuse an
 * unverifiable location identically. No refusal detail carries a host path or a value of the request.
 */
public final class WriteLocation {
  private static final String REF_COMMIT_DETAIL = "coordination_ref_commit is unavailable until migration step 4.";
  private static final String COORDINATION_REF_COMMIT = "coordination_ref_commit";

  /** Either the Mission whose location was verified, or the refusal to answer. */
  public sealed interface Result permits Verified, Refused {}

  public record Verified(MissionRef mission) implements Result {}

  public record Refused(RefusalCode code, String detail) implements Result {}

  private WriteLocation() {}

  public static Result verify(MissionCatalog missions, String missionId, WriteRequest.Context context) {
    if (COORDINATION_REF_COMMIT.equals(context.writeSurface())) {
      return new Refused(RefusalCode.WRITE_LOCATION_UNVERIFIED, REF_COMMIT_DETAIL);
    }
    MissionCatalog.FindResult found = missions.find(missionId);
    if (found instanceof MissionCatalog.RootUnverifiable) {
      return new Refused(RefusalCode.WRITE_LOCATION_UNVERIFIED, "The repository root checkout cannot be verified.");
    }
    if (!(found instanceof MissionCatalog.Found success)) {
      return new Refused(RefusalCode.MISSION_NOT_FOUND, "The Mission was not found.");
    }
    MissionRef mission = success.mission();
    if (!missions.locationMatches(mission, context.checkout(), context.missionDirectory(), context.destinationRef())) {
      return new Refused(RefusalCode.WRITE_LOCATION_UNVERIFIED, "The write location could not be verified.");
    }
    return new Verified(mission);
  }
}
