package org.speckitty.commandcli.adapter.out.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The state directory core's launcher and endpoint reader expect: a real directory, owner-only (core ADR 2026-10-27-1 D-2). */
@Tag("native")
class StateDirectoryTest {

  @TempDir Path gcd;

  @Test
  void createsTheDirectoryOwnerOnly() throws Exception {
    StateDirectory directory = StateDirectory.prepare(gcd);

    assertThat(directory.path()).isEqualTo(gcd.resolve("spec-kitty-write-service")).isDirectory();
    assumeTrue(OwnerOnly.posix(gcd));
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.path()))).isEqualTo("rwx------");
  }

  @Test
  void reusesAnExistingDirectoryAndTightensALooseMode() throws Exception {
    assumeTrue(OwnerOnly.posix(gcd));
    Path existing = Files.createDirectory(gcd.resolve(StateDirectory.NAME), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
    Files.writeString(existing.resolve("service.log"), "kept\n");

    StateDirectory directory = StateDirectory.prepare(gcd);

    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.path()))).isEqualTo("rwx------");
    assertThat(existing.resolve("service.log")).hasContent("kept");
  }

  @Test
  void refusesASymbolicLinkAndNeverFollowsIt() throws Exception {
    Path elsewhere = Files.createDirectory(gcd.resolve("elsewhere"));
    try {
      Files.createSymbolicLink(gcd.resolve(StateDirectory.NAME), elsewhere);
    } catch (UnsupportedOperationException | java.io.IOException noLinks) {
      assumeTrue(false, "symbolic links are not available here");
    }

    assertThatThrownBy(() -> StateDirectory.prepare(gcd)).isInstanceOf(StateDirectoryRefused.class).hasMessageContaining("symbolic link");
    assertThat(Files.isSymbolicLink(gcd.resolve(StateDirectory.NAME))).isTrue();
  }

  @Test
  void refusesAFileInThePlaceOfTheDirectory() throws Exception {
    Files.writeString(gcd.resolve(StateDirectory.NAME), "not a directory");

    assertThatThrownBy(() -> StateDirectory.prepare(gcd)).isInstanceOf(StateDirectoryRefused.class).hasMessageContaining("not a directory");
  }

  @Test
  void refusesAGitCommonDirThatIsGone() {
    assertThatThrownBy(() -> StateDirectory.prepare(gcd.resolve("gone"))).isInstanceOf(StateDirectoryRefused.class).hasMessageNotContaining(gcd.toString());
  }

  @Test
  void resolvesNamesInsideTheDirectory() throws Exception {
    StateDirectory directory = StateDirectory.prepare(gcd);

    assertThat(directory.resolve("endpoint.json")).isEqualTo(gcd.resolve(StateDirectory.NAME).resolve("endpoint.json"));
    assertThat(Files.isDirectory(directory.path(), LinkOption.NOFOLLOW_LINKS)).isTrue();
  }
}
