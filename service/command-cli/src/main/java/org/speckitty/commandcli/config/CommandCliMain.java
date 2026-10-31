package org.speckitty.commandcli.config;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Optional;
import org.speckitty.commandcli.adapter.out.filesystem.RepositoryIdentity;
import org.speckitty.commandcli.adapter.out.filesystem.ServiceLock;
import org.speckitty.commandcli.adapter.out.filesystem.StateDirectory;
import org.speckitty.commandcli.adapter.out.filesystem.StateDirectoryRefused;
import org.speckitty.commandcli.domain.service.InstanceId;
import org.speckitty.commandcli.domain.service.WriteToken;
import org.springframework.boot.SpringApplication;

/**
 * The process entry point: {@code command-cli serve --git-common-dir <abs> [--idle-timeout <seconds>]} (contracts/mission-write/LAUNCHER.md).
 * In order: the command line (a usage error exits 2), the state directory (a link or an unsafe directory exits 1), the single-instance lock
 * (held by another instance: exit 0, nothing published, nothing bound), then the instance, the token and the repository identity, and only
 * then the application, which publishes the endpoint file once its server is bound.
 */
public final class CommandCliMain {

  /** Set by Spring's AOT processor while it runs main() at build time: no command line is given and nothing is served then. */
  static final String AOT_PROCESSING = "spring.aot.processing";

  static final int EXIT_OK = 0;
  static final int EXIT_FAILURE = 1;
  static final int EXIT_USAGE = 2;

  static final String DEVELOPMENT_VERSION = "0.0.0-dev";

  private CommandCliMain() {}

  public static void main(String[] args) {
    if (Boolean.getBoolean(AOT_PROCESSING)) {
      SpringApplication.run(CommandCliApplication.class);
      return;
    }
    ServeOptions options;
    try {
      options = ServeOptions.parse(args);
    } catch (UsageException e) {
      System.err.println(e.getMessage());
      System.exit(EXIT_USAGE);
      return;
    }
    ServeRuntime runtime;
    try {
      Optional<ServeRuntime> prepared = prepare(options);
      if (prepared.isEmpty()) {
        System.err.println("command-cli: another write service holds the lock for this git common dir; exiting without publishing");
        System.exit(EXIT_OK);
        return;
      }
      runtime = prepared.get();
    } catch (StateDirectoryRefused e) {
      System.err.println("command-cli: " + e.getMessage());
      System.exit(EXIT_FAILURE);
      return;
    } catch (IOException e) {
      System.err.println("command-cli: the service cannot start (" + e.getClass().getSimpleName() + ")");
      System.exit(EXIT_FAILURE);
      return;
    }
    ServeRuntime.install(runtime);
    new SpringApplication(CommandCliApplication.class).run();
  }

  /** The state directory, the lock and the identity; empty when another instance holds the lock. */
  static Optional<ServeRuntime> prepare(ServeOptions options) throws StateDirectoryRefused, IOException {
    StateDirectory directory = StateDirectory.prepare(options.gitCommonDir());
    Optional<ServiceLock> lock = ServiceLock.tryAcquire(directory);
    if (lock.isEmpty()) {
      return Optional.empty();
    }
    SecureRandom random = new SecureRandom();
    return Optional.of(
        new ServeRuntime(
            options,
            directory,
            lock.get(),
            InstanceId.mint(System.currentTimeMillis(), random),
            WriteToken.mint(random),
            RepositoryIdentity.of(options.gitCommonDir()),
            serviceVersion(),
            System::exit));
  }

  /** The build's version from META-INF/build-info.properties (the release passes -PreleaseVersion), or 0.0.0-dev when it has none. */
  static String serviceVersion() {
    try (java.io.InputStream in = CommandCliMain.class.getClassLoader().getResourceAsStream(CommandCliHints.BUILD_INFO)) {
      if (in == null) {
        return DEVELOPMENT_VERSION;
      }
      java.util.Properties properties = new java.util.Properties();
      properties.load(in);
      String version = properties.getProperty("build.version");
      return version == null || version.isBlank() ? DEVELOPMENT_VERSION : version.trim();
    } catch (IOException e) {
      return DEVELOPMENT_VERSION;
    }
  }
}
