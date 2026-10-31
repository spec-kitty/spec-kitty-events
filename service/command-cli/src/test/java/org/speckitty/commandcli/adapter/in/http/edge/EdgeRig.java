package org.speckitty.commandcli.adapter.in.http.edge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.speckitty.commandcli.adapter.out.filesystem.RepositoryIdentity;
import org.speckitty.commandcli.adapter.out.filesystem.ServiceLock;
import org.speckitty.commandcli.adapter.out.filesystem.StateDirectory;
import org.speckitty.commandcli.config.CommandCliApplication;
import org.speckitty.commandcli.config.ServeOptions;
import org.speckitty.commandcli.config.ServeRuntime;
import org.speckitty.commandcli.domain.service.InstanceId;
import org.speckitty.commandcli.domain.service.WriteToken;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The production application on an ephemeral port, serving a temporary git common dir, with the runtime {@code CommandCliMain} would
 * install (state directory, held lock, instance, token, identity) except that an exit is recorded instead of ending the test JVM. Requests
 * go over raw sockets.
 */
public final class EdgeRig implements AutoCloseable {

  public final Path gitCommonDir;
  public final ServeRuntime runtime;
  public final WriteToken token;
  public final List<Integer> exits = new CopyOnWriteArrayList<>();
  private final ConfigurableApplicationContext context;

  private EdgeRig(Duration idleTimeout, String... args) throws IOException {
    gitCommonDir = Files.createTempDirectory("command-cli-gcd");
    StateDirectory directory;
    try {
      directory = StateDirectory.prepare(gitCommonDir);
    } catch (org.speckitty.commandcli.adapter.out.filesystem.StateDirectoryRefused e) {
      throw new IOException(e.getMessage());
    }
    ServiceLock lock = ServiceLock.tryAcquire(directory).orElseThrow();
    SecureRandom random = new SecureRandom();
    token = WriteToken.mint(random);
    runtime =
        new ServeRuntime(
            new ServeOptions(gitCommonDir, idleTimeout),
            directory,
            lock,
            InstanceId.mint(System.currentTimeMillis(), random),
            token,
            RepositoryIdentity.of(gitCommonDir),
            "0.0.0-dev",
            exits::add);
    ServeRuntime.install(runtime);
    context = new SpringApplicationBuilder(CommandCliApplication.class).run(args);
  }

  public static EdgeRig start(String... args) {
    return start(ServeOptions.DEFAULT_IDLE_TIMEOUT, args);
  }

  public static EdgeRig start(Duration idleTimeout, String... args) {
    try {
      return new EdgeRig(idleTimeout, args);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  public int port() {
    return context.getBean(OwnPortSource.class).port();
  }

  public ConfigurableApplicationContext context() {
    return context;
  }

  public String host() {
    return "Host: 127.0.0.1:" + port();
  }

  public String bearer() {
    return "Authorization: Bearer " + token.reveal();
  }

  public RawClient.Reply request(String requestLine, String body, String... headerLines) {
    return RawClient.request(port(), requestLine, body, headerLines);
  }

  /** Every file under the git common dir with its size and modification time, for "nothing was written" checks. */
  public List<String> tree() {
    try (Stream<Path> files = Files.walk(gitCommonDir)) {
      return files
          .filter(Files::isRegularFile)
          .sorted()
          .map(p -> {
            try {
              return gitCommonDir.relativize(p) + " " + Files.size(p) + " " + Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
              return gitCommonDir.relativize(p) + " ?";
            }
          })
          .toList();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    if (context.isActive()) {
      context.close();
    }
    try {
      runtime.lock().close();
    } catch (IOException ignored) {
      // the temporary directory is removed next
    }
    try (Stream<Path> files = Files.walk(gitCommonDir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // best effort
    }
  }
}
