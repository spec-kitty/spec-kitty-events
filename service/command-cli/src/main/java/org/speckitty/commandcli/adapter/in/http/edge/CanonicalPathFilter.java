package org.speckitty.commandcli.adapter.in.http.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The edge decides on the text the dispatcher routes on. The protocol and size checks match the raw request target, while Spring routes on
 * the decoded target with path parameters and dot segments removed; a target such as {@code /api/v1/shutdow%6E} or
 * {@code .../work-package-transitions;x=1} would reach an operation the raw match did not see. No client of the contract needs such a target
 * (every path segment is ASCII and every identifier a ULID or a WpId), so a target that is not in canonical form is refused 400
 * {@code REQUEST_MALFORMED} right after the token: no path parameter ({@code ;}), no percent-encoding ({@code %}), no empty segment
 * ({@code //}) and no dot segment. A query string is not part of the target checked.
 */
public class CanonicalPathFilter extends OncePerRequestFilter {

  static final String DETAIL = "The request target is not in canonical form.";

  private final JsonMapper mapper;

  public CanonicalPathFilter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  /** True when {@code target} (a request URI without the query) is canonical. */
  public static boolean canonical(String target) {
    if (target == null || target.isEmpty() || target.charAt(0) != '/') {
      return false;
    }
    if (target.indexOf(';') >= 0 || target.indexOf('%') >= 0 || target.indexOf('\\') >= 0 || target.contains("//")) {
      return false;
    }
    for (String segment : target.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    if (canonical(request.getRequestURI())) {
      chain.doFilter(request, response);
      return;
    }
    RefusalBody.of(RefusalCode.REQUEST_MALFORMED, DETAIL).write(response, mapper);
  }
}
