package org.speckitty.commandcli.adapter.in.http.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.speckitty.commandcli.domain.service.ProtocolSupport;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The protocol check, third in the contract's order (contracts/mission-write parameters/WriteProtocolVersion.yaml): every operation but
 * the handshake and the health must carry {@code Spec-Kitty-Write-Protocol} exactly once, naming a major the service speaks. Otherwise 400
 * {@code PROTOCOL_VERSION_UNSUPPORTED}, whose detail lists the supported majors. Paths that are no operation are left to their 404.
 */
public class ProtocolHeaderFilter extends OncePerRequestFilter {

  public static final String HEADER = "Spec-Kitty-Write-Protocol";

  private final JsonMapper mapper;

  public ProtocolHeaderFilter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !Operations.needsProtocol(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    List<String> values = Collections.list(request.getHeaders(HEADER));
    if (values.size() == 1 && ProtocolSupport.accepts(values.get(0))) {
      chain.doFilter(request, response);
      return;
    }
    RefusalBody.of(RefusalCode.PROTOCOL_VERSION_UNSUPPORTED, ProtocolSupport.supportedDetail()).write(response, mapper);
  }
}
