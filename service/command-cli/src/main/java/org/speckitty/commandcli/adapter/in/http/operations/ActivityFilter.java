package org.speckitty.commandcli.adapter.in.http.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts the requests in flight and the time of the last one (the drain waits for the first, the idle exit reads the second). It runs after
 * the guard and the token, so a refused stranger neither keeps the service alive nor delays its drain.
 */
class ActivityFilter extends OncePerRequestFilter {

  private final ServiceLifecycle lifecycle;

  ActivityFilter(ServiceLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    lifecycle.enter();
    try {
      chain.doFilter(request, response);
      response.flushBuffer();
    } finally {
      lifecycle.exit();
    }
  }
}
