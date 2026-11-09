package org.speckitty.commandcli.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.commandcli.adapter.out.filesystem.RepositoryIdentity;
import org.speckitty.commandcli.adapter.out.filesystem.ServiceLock;
import org.speckitty.commandcli.adapter.out.filesystem.StateDirectory;
import org.speckitty.commandcli.domain.service.InstanceId;
import org.speckitty.commandcli.domain.service.WriteToken;

/** Production composition uses the contract's one shared ten-second write budget (R7). */
class ServiceWiringTest {
  @TempDir Path repository;

  @Test
  void writeServiceBudgetIsExactlyTenSeconds() throws Exception {
    Process git = new ProcessBuilder("git", "init", "--quiet").directory(repository.toFile()).start();
    assertThat(git.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(git.exitValue()).isZero();
    Path gcd = repository.resolve(".git");
    StateDirectory state = StateDirectory.prepare(gcd);
    try (ServiceLock lock = ServiceLock.tryAcquire(state).orElseThrow()) {
      SecureRandom random = new SecureRandom();
      ServeRuntime runtime = new ServeRuntime(new ServeOptions(gcd, Duration.ofMinutes(1)), state, lock,
          InstanceId.mint(System.currentTimeMillis(), random), WriteToken.mint(random), RepositoryIdentity.of(gcd),
          "test", ignored -> { });
      assertThat(new ServiceWiring().writeService(runtime).budget()).isEqualTo(Duration.ofSeconds(10));
    }
  }
}
