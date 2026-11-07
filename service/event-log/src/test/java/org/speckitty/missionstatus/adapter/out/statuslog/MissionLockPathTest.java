package org.speckitty.missionstatus.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissionLockPathTest {

  @TempDir Path gcd;

  @Test
  void derivesTheCoreStatusLockPathFromTheMissionDirectoryName() {
    assertThat(MissionLockPath.of(gcd, "write-core-01M6TP6W"))
        .isEqualTo(gcd.resolve("spec-kitty-locks/write-core-01M6TP6W.status.lock"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".", "..", "a/b", "a\\b", "a\u0000b"})
  void refusesAnythingButOneSafeDirectoryName(String name) {
    assertThatIllegalArgumentException().isThrownBy(() -> MissionLockPath.of(gcd, name));
  }

  @Test
  void refusesARelativeGitCommonDirectory() {
    assertThatIllegalArgumentException().isThrownBy(() -> MissionLockPath.of(Path.of("relative/.git"), "mission"));
  }
}
