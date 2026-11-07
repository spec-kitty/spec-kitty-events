package org.speckitty.missionstatus.adapter.out.statuslog;

import java.nio.file.Path;

/** The status lock shared with core, keyed by the Mission directory's basename rather than its slug or mission id. */
public final class MissionLockPath {

  private MissionLockPath() {}

  /**
   * Returns {@code <gcd>/spec-kitty-locks/<missionDirectoryName>.status.lock}.
   *
   * @param gcd the absolute Git common directory
   * @param missionDirectoryName one non-empty, safe directory-name segment
   */
  public static Path of(Path gcd, String missionDirectoryName) {
    if (gcd == null || !gcd.isAbsolute()) {
      throw new IllegalArgumentException("the Git common directory must be absolute");
    }
    if (missionDirectoryName == null
        || missionDirectoryName.isEmpty()
        || missionDirectoryName.equals(".")
        || missionDirectoryName.equals("..")
        || missionDirectoryName.indexOf('/') >= 0
        || missionDirectoryName.indexOf('\\') >= 0
        || missionDirectoryName.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("the Mission lock key must be one safe directory name");
    }
    return gcd.resolve("spec-kitty-locks").resolve(missionDirectoryName + ".status.lock");
  }
}
