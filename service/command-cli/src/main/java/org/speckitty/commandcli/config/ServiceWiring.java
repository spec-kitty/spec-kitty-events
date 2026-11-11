package org.speckitty.commandcli.config;

import java.time.Clock;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.speckitty.commandcli.adapter.out.filesystem.EndpointFile;
import org.speckitty.commandcli.adapter.out.mission.FilesystemMissionCatalog;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogRowCodec;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogStatusLog;
import org.speckitty.commandcli.application.lifecycle.IdleWatch;
import org.speckitty.commandcli.application.lifecycle.ServiceIdentity;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.speckitty.commandcli.application.repair.StatusLogRepairService;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.commandcli.application.write.WriteService;
import org.speckitty.commandcli.application.write.WriteTurn;
import org.speckitty.commandcli.domain.authority.Authority;
import org.speckitty.commandcli.domain.service.WriteToken;
import org.speckitty.missionstatus.adapter.out.statuslog.StatusLogAppender;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The composition root: the per-process values of {@link ServeRuntime} become beans, the lifecycle is wired to the process end
 * ({@link ProcessShutdown}), and once the web server is bound the endpoint file is published, the signal hook is registered and the idle
 * watch starts.
 */
@Configuration(proxyBeanMethods = false)
class ServiceWiring {

  /** The repository root checkout, as its own type so the catalog is injected only through its port. */
  record RepositoryRoot(java.nio.file.Path path) {}

  @Bean
  ServeRuntime serveRuntime() {
    return ServeRuntime.current();
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  WriteToken writeToken(ServeRuntime runtime) {
    return runtime.token();
  }

  @Bean
  ServiceIdentity serviceIdentity(ServeRuntime runtime) {
    return new ServiceIdentity(runtime.instance(), runtime.repository(), runtime.serviceVersion());
  }

  @Bean
  EndpointFile endpointFile(ServeRuntime runtime, JsonMapper mapper) {
    return new EndpointFile(runtime.directory(), mapper);
  }

  @Bean
  ProcessShutdown processShutdown(ServeRuntime runtime, EndpointFile endpoint, ApplicationContext context) {
    return new ProcessShutdown(endpoint, runtime.instance(), runtime.lock(), () -> SpringApplication.exit(context), runtime.exit());
  }

  @Bean
  ServiceLifecycle serviceLifecycle(Clock clock, ProcessShutdown shutdown) {
    return new ServiceLifecycle(clock, shutdown);
  }

  /** The one in-process turn that serialises every mutation of a Mission: a write and a repair wait on this same instance. */
  @Bean
  WriteTurn writeTurn() {
    return new WriteTurn();
  }

  /** The one write budget a write and a repair share across the turn and the Mission lock (R7). A test overrides it with a {@code @Primary} Duration bean. */
  @Bean
  Duration defaultWriteBudget() {
    return Duration.ofSeconds(10);
  }

  @Bean
  LongSupplier nanoClock() {
    return System::nanoTime;
  }

  @Bean
  FilesystemMissionCatalog missionCatalog(ServeRuntime runtime) {
    return new FilesystemMissionCatalog(runtime.options().gitCommonDir());
  }

  /** The repository root checkout: the catalog's resolution, else the git common dir's parent. */
  @Bean
  RepositoryRoot repositoryRoot(ServeRuntime runtime, FilesystemMissionCatalog missions) {
    java.nio.file.Path gcd = runtime.options().gitCommonDir();
    return new RepositoryRoot(missions.root().orElse(gcd.toAbsolutePath().getParent()));
  }

  @Bean
  StatusLog statusLog(ServeRuntime runtime, RepositoryRoot root) {
    return new EventLogStatusLog(new StatusLogAppender(), runtime.options().gitCommonDir(), root.path());
  }

  @Bean
  RowCodec rowCodec() {
    return new EventLogRowCodec();
  }

  @Bean
  WriteService writeService(RepositoryRoot root, MissionCatalog missions, StatusLog statusLog, RowCodec codec,
      WriteTurn turn, Duration writeBudget, LongSupplier nanoClock, ServiceLifecycle lifecycle) {
    return new WriteService(missions, statusLog, codec, turn, Authority.standard(root.path().toString()), writeBudget,
        nanoClock, lifecycle::draining);
  }

  @Bean
  StatusLogRepairService statusLogRepairService(MissionCatalog missions, StatusLog statusLog, RowCodec codec, WriteTurn turn,
      Duration writeBudget, LongSupplier nanoClock, ServiceLifecycle lifecycle) {
    return new StatusLogRepairService(missions, statusLog, codec, turn, writeBudget, nanoClock, lifecycle::draining);
  }

  @Bean
  IdleWatch idleWatch(ServeRuntime runtime, ServiceLifecycle lifecycle) {
    return new IdleWatch(lifecycle, runtime.options().idleTimeout());
  }

  @Bean
  EndpointPublisher endpointPublisher(EndpointFile endpoint, ServiceIdentity identity, WriteToken token, Clock clock) {
    return new EndpointPublisher(endpoint, identity, token, clock);
  }

  @Bean
  IdleTicker idleTicker(IdleWatch watch, ServiceLifecycle lifecycle) {
    return new IdleTicker(watch, lifecycle);
  }

  @Bean
  ProcessHooks processHooks(EndpointPublisher publisher, ProcessShutdown shutdown, IdleTicker ticker) {
    return new ProcessHooks(publisher, shutdown, ticker);
  }

  /** Once the server is bound: publish the endpoint file, then hook the signal path and start the idle watch. */
  @Bean
  ApplicationListener<WebServerInitializedEvent> whenBound(ProcessHooks hooks) {
    return event -> hooks.bound(event.getWebServer().getPort());
  }
}
