package org.speckitty.commandcli.config;

import java.util.Objects;
import java.util.function.IntConsumer;
import org.speckitty.commandcli.adapter.out.filesystem.ServiceLock;
import org.speckitty.commandcli.adapter.out.filesystem.StateDirectory;
import org.speckitty.commandcli.domain.service.InstanceId;
import org.speckitty.commandcli.domain.service.RepositoryId;
import org.speckitty.commandcli.domain.service.WriteToken;

/**
 * What {@link CommandCliMain} establishes before the application starts: the options, the state directory, the held single-instance lock,
 * the instance, the token, the repository identity, the version and the way the process exits. The application's bean definitions are fixed
 * at build time for the native image (Spring AOT), so the per-process values reach them through this holder, installed once before
 * {@code SpringApplication.run}.
 */
public record ServeRuntime(
    ServeOptions options,
    StateDirectory directory,
    ServiceLock lock,
    InstanceId instance,
    WriteToken token,
    RepositoryId repository,
    String serviceVersion,
    IntConsumer exit) {

  /** One per process; tests that start the application install their own and run one after another (JUnit runs classes serially here). */
  private static volatile ServeRuntime current;

  public ServeRuntime {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(lock, "lock");
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(serviceVersion, "serviceVersion");
    Objects.requireNonNull(exit, "exit");
  }

  /** Installs the runtime of this process (tests install their own). */
  public static void install(ServeRuntime runtime) {
    current = Objects.requireNonNull(runtime, "runtime");
  }

  /** The installed runtime; the application cannot start without one. */
  public static ServeRuntime current() {
    ServeRuntime runtime = current;
    if (runtime == null) {
      throw new IllegalStateException("command-cli: no serve runtime is installed (start the service with `serve --git-common-dir <abs>`)");
    }
    return runtime;
  }

  @Override
  public String toString() {
    return "ServeRuntime[instance=" + instance + ", token=redacted]";
  }
}
