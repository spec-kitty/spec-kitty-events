package org.speckitty.commandcli.adapter.out.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.commandcli.domain.service.RepositoryId;

/** The repository identity matches core's {@code repository_id(gcd) = sha256(gcd.resolve().as_posix())}. */
@Tag("native")
class RepositoryIdentityTest {

  @TempDir Path root;

  @Test
  void hashesTheRealPathWithForwardSlashes() throws Exception {
    Path gcd = Files.createDirectory(root.resolve(".git"));

    String real = RepositoryIdentity.posixRealPath(gcd);

    assertThat(real).doesNotContain("\\").isEqualTo(gcd.toRealPath().toString().replace('\\', '/'));
    assertThat(RepositoryIdentity.of(gcd)).isEqualTo(RepositoryIdentity.ofRealPath(real));
  }

  @Test
  void theDigestIsCoresRepositoryId() {
    // python3 -c "import hashlib;print(hashlib.sha256('/repo/.git'.encode()).hexdigest())"
    assertThat(RepositoryIdentity.ofRealPath("/repo/.git"))
        .isEqualTo(new RepositoryId("6b4ca2db35cfaf43d6963005a8445402c4ea6f70b038086ad0d6decda64670c0"));
  }

  @Test
  void aLinkToTheGitCommonDirHasTheSameIdentityAsTheDirectory() throws Exception {
    Path gcd = Files.createDirectory(root.resolve(".git"));
    Path link = root.resolve("link");
    try {
      Files.createSymbolicLink(link, gcd);
    } catch (UnsupportedOperationException | java.io.IOException noLinks) {
      assumeTrue(false, "symbolic links are not available here");
    }

    assertThat(RepositoryIdentity.of(link)).isEqualTo(RepositoryIdentity.of(gcd));
  }
}
