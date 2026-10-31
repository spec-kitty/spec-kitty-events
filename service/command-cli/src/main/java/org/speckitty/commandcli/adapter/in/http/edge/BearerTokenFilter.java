package org.speckitty.commandcli.adapter.in.http.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.speckitty.commandcli.domain.service.WriteToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bearer check, second in the contract's order (after the Host/Origin guard, before the protocol header): every request, on every path
 * (an unknown path included, so a caller without the token learns nothing about the routes), must carry exactly one
 * {@code Authorization: Bearer <token>} with this instance's token. Otherwise 401 {@code WRITE_TOKEN_INVALID} with
 * {@code WWW-Authenticate: Bearer}. The presented value is compared in constant time and never logged or echoed.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

  static final String SCHEME = "Bearer ";

  private final WriteToken token;
  private final JsonMapper mapper;

  public BearerTokenFilter(WriteToken token, JsonMapper mapper) {
    this.token = token;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    if (authorized(request)) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("WWW-Authenticate", "Bearer");
    RefusalBody.of(RefusalCode.WRITE_TOKEN_INVALID).write(response, mapper);
  }

  private boolean authorized(HttpServletRequest request) {
    List<String> values = Collections.list(request.getHeaders("Authorization"));
    if (values.size() != 1) {
      return false;
    }
    String value = values.get(0);
    if (!value.startsWith(SCHEME)) {
      return false;
    }
    return token.matches(value.substring(SCHEME.length()));
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }
}
