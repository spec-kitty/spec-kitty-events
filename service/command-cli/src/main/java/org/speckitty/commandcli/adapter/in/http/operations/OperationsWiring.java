package org.speckitty.commandcli.adapter.in.http.operations;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/** The beans of the operations adapter: the shape check, the activity count (after the guard and the token) and the drain executor. */
@Configuration(proxyBeanMethods = false)
class OperationsWiring {

  /**
   * After the guard, the token, the canonical target and the protocol (HIGHEST_PRECEDENCE to +3), so a refused stranger is not counted, and
   * before the size cap (+5) reads the body, so a request whose body is still arriving holds the drain (pre-PR squad, debugger finding 1).
   */
  static final int ACTIVITY = Ordered.HIGHEST_PRECEDENCE + 4;

  @Bean
  WriteBodies writeBodies(JsonMapper mapper) {
    return new WriteBodies(mapper);
  }

  @Bean
  FilterRegistrationBean<ActivityFilter> activityFilter(ServiceLifecycle lifecycle) {
    FilterRegistrationBean<ActivityFilter> registration = new FilterRegistrationBean<>(new ActivityFilter(lifecycle));
    registration.setOrder(ACTIVITY);
    registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
    registration.addUrlPatterns("/*");
    return registration;
  }

  @Bean
  DrainExecutor drainExecutor() {
    return command -> Thread.ofPlatform().name("command-cli-drain").daemon(false).start(command);
  }
}
