package org.speckitty.commandcli.config;

import java.time.Clock;
import java.time.Duration;
import org.speckitty.commandcli.adapter.out.filesystem.EndpointFile;
import org.speckitty.commandcli.adapter.out.mission.FilesystemMissionCatalog;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogRowCodec;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogStatusLog;
import org.speckitty.commandcli.application.lifecycle.IdleWatch;
import org.speckitty.commandcli.application.lifecycle.ServiceIdentity;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
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

  @Bean
  WriteService writeService(ServeRuntime runtime) {
    java.nio.file.Path gcd = runtime.options().gitCommonDir();
    FilesystemMissionCatalog missions = new FilesystemMissionCatalog(gcd);
    java.nio.file.Path root = missions.root().orElse(gcd.toAbsolutePath().getParent());
    return new WriteService(missions, new EventLogStatusLog(new StatusLogAppender(), gcd, root), new EventLogRowCodec(),
        new WriteTurn(), Authority.standard(), Duration.ofSeconds(10), System::nanoTime);
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
