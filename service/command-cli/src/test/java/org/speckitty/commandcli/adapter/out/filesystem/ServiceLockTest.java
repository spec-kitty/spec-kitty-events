package org.speckitty.commandcli.adapter.out.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The single-instance lock of LAUNCHER.md: one holder per git common dir, across processes. */
class ServiceLockTest {

  @TempDir Path gcd;

  @Test
  void aSecondAcquireInTheSameProcessIsRefusedUntilTheFirstCloses() throws Exception {
    StateDirectory directory = StateDirectory.prepare(gcd);

    try (ServiceLock first = ServiceLock.tryAcquire(directory).orElseThrow()) {
      assertThat(first.held()).isTrue();
      assertThat(ServiceLock.tryAcquire(directory)).isEmpty();
      // the refused attempt must not have dropped the OS lock (closing a second descriptor would): another process is still refused
      assertThat(runHolder(directory.path())).isEqualTo("REFUSED");
    }
    Optional<ServiceLock> again = ServiceLock.tryAcquire(directory);
    assertThat(again).isPresent();
    again.get().close();
  }

  @Test
  void anotherProcessCannotTakeTheLockWhileThisOneHoldsIt() throws Exception {
    StateDirectory directory = StateDirectory.prepare(gcd);
    try (ServiceLock held = ServiceLock.tryAcquire(directory).orElseThrow()) {
      assertThat(runHolder(directory.path())).isEqualTo("REFUSED");
    }
    assertThat(runHolder(directory.path())).isEqualTo("ACQUIRED");
  }

  @Test
  void thisProcessCannotTakeTheLockWhileAnotherProcessHoldsIt() throws Exception {
    StateDirectory directory = StateDirectory.prepare(gcd);
    Process holder = holder(directory.path(), "hold");
    try (BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
      assertThat(out.readLine()).isEqualTo("ACQUIRED");
      assertThat(ServiceLock.tryAcquire(directory)).isEmpty();
    } finally {
      holder.getOutputStream().close(); // the holder exits when its stdin closes
      assertThat(holder.waitFor(30, TimeUnit.SECONDS)).isTrue();
    }
    Optional<ServiceLock> after = ServiceLock.tryAcquire(directory);
    assertThat(after).isPresent();
    after.get().close();
  }

  private static String runHolder(Path directory) throws Exception {
    Process process = holder(directory, "probe");
    String line;
    try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      line = out.readLine();
    }
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    return line;
  }

  private static Process holder(Path directory, String mode) throws Exception {
    String java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    ProcessBuilder builder =
        new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"), LockHolder.class.getName(), directory.toString(), mode));
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    return builder.start();
  }
}
