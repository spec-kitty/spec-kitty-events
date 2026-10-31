package org.speckitty.commandcli.adapter.in.http.edge;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.speckitty.commandcli.domain.service.WriteToken;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the edge checks in the contract's order (openapi.yaml description): the Host/Origin guard (403), the bearer token (401), a
 * canonical request target (400, so the checks below decide on the text the dispatcher routes on), the protocol header (400), the size cap
 * (413, on every body); the body's shape and the domain follow in the controllers. The guard and the token also run on
 * error dispatches, so the container's own errors are guarded too.
 */
@Configuration(proxyBeanMethods = false)
class EdgeFilters {

  static final int GUARD = Ordered.HIGHEST_PRECEDENCE;
  static final int TOKEN = GUARD + 1;
  static final int CANONICAL = GUARD + 2;
  static final int PROTOCOL = GUARD + 3;
  /** After the activity count (+4, operations), so a request whose body is still arriving counts as in flight. */
  static final int SIZE = GUARD + 5;

  @Bean
  FilterRegistrationBean<HostOriginFilter> hostOriginFilter(OwnPortSource ownPort, JsonMapper mapper) {
    return register(new HostOriginFilter(ownPort, mapper), GUARD, true);
  }

  @Bean
  FilterRegistrationBean<BearerTokenFilter> bearerTokenFilter(WriteToken token, JsonMapper mapper) {
    return register(new BearerTokenFilter(token, mapper), TOKEN, true);
  }

  @Bean
  FilterRegistrationBean<CanonicalPathFilter> canonicalPathFilter(JsonMapper mapper) {
    return register(new CanonicalPathFilter(mapper), CANONICAL, false);
  }

  @Bean
  FilterRegistrationBean<ProtocolHeaderFilter> protocolHeaderFilter(JsonMapper mapper) {
    return register(new ProtocolHeaderFilter(mapper), PROTOCOL, false);
  }

  @Bean
  FilterRegistrationBean<BodySizeFilter> bodySizeFilter(JsonMapper mapper) {
    return register(new BodySizeFilter(mapper), SIZE, false);
  }

  private static <F extends jakarta.servlet.Filter> FilterRegistrationBean<F> register(F filter, int order, boolean errorDispatch) {
    FilterRegistrationBean<F> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(order);
    registration.setDispatcherTypes(errorDispatch ? EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR) : EnumSet.of(DispatcherType.REQUEST));
    registration.addUrlPatterns("/*");
    return registration;
  }
}
