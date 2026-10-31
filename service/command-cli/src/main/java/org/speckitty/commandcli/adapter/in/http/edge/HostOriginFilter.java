package org.speckitty.commandcli.adapter.in.http.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Host and Origin guard, copied from the read service's {@code HostOriginFilter} (operator ruling of 2026-10-31 on #156; core ADR
 * 2026-10-27-1: "every request passes the read service's guard"). It is the first check of the contract's order (403 before 401): a
 * servlet filter at the highest precedence, on every path and on error dispatches.
 *
 * <ul>
 *   <li>The RAW {@code Host} header is compared, never {@code getServerName()}, exactly and case-sensitively against
 *       {@code 127.0.0.1:<own port>} and {@code localhost:<own port>}; an absent or empty header is refused.
 *   <li>{@code Forwarded} and {@code X-Forwarded-Host}, present with any value, are refused.
 *   <li>{@code Origin}, when present, must be {@code http://} plus one of the two accepted host forms.
 *   <li>{@code Sec-Fetch-Site: cross-site} is refused.
 * </ul>
 *
 * Every refusal is 403 {@code REQUEST_REFUSED} (the write contract's code; the read service's is {@code request_refused}); the body names
 * no check and carries no request value.
 */
public class HostOriginFilter extends OncePerRequestFilter {

  private final OwnPortSource ownPort;
  private final JsonMapper mapper;

  public HostOriginFilter(OwnPortSource ownPort, JsonMapper mapper) {
    this.ownPort = ownPort;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    if (accepted(request)) {
      chain.doFilter(request, response);
      return;
    }
    RefusalBody.of(RefusalCode.REQUEST_REFUSED).write(response, mapper);
  }

  private boolean accepted(HttpServletRequest request) {
    int port = ownPort.port();
    if (port < 0) {
      return false; // no server is bound yet: nothing is accepted
    }
    String loopback = "127.0.0.1:" + port;
    String named = "localhost:" + port;
    String host = request.getHeader("Host");
    if (host == null || !(host.equals(loopback) || host.equals(named))) {
      return false;
    }
    if (request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-Host") != null) {
      return false;
    }
    if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
      return false;
    }
    List<String> origins = Collections.list(request.getHeaders("Origin"));
    for (String origin : origins) {
      if (!(origin.equals("http://" + loopback) || origin.equals("http://" + named))) {
        return false;
      }
    }
    return true;
  }

  /** The guard covers error paths: a request the container answers with its own sendError reaches the error page through an ERROR dispatch. */
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }
}
