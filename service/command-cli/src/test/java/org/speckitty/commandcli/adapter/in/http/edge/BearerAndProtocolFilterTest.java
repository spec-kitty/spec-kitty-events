package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.service.WriteToken;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** The token and protocol filters in isolation: values the container would normalise, and the token's ERROR dispatch. */
@Tag("native")
class BearerAndProtocolFilterTest {

  private final WriteToken token = WriteToken.mint(new SecureRandom());
  private final JsonMapper mapper = JsonMapper.builder().build();

  private record Outcome(int status, boolean passed, String body, String authenticate) {}

  private static Outcome run(OncePerRequestFilter filter, HttpServletRequest request) throws ServletException, IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicInteger passed = new AtomicInteger();
    filter.doFilter(request, response, new MockFilterChain() {
      @Override
      public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
        passed.incrementAndGet();
      }
    });
    return new Outcome(response.getStatus(), passed.get() == 1, response.getContentAsString(StandardCharsets.UTF_8), response.getHeader("WWW-Authenticate"));
  }

  private MockHttpServletRequest request(String method, String path, String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    return request;
  }

  @Test
  void theTokenPassesExactlyOnce() throws Exception {
    BearerTokenFilter filter = new BearerTokenFilter(token, mapper);
    assertThat(run(filter, request("GET", "/api/v1/health", "Bearer " + token.reveal())).passed()).isTrue();
  }

  @Test
  void anEmptyOrMalformedTokenIsRefused() throws Exception {
    BearerTokenFilter filter = new BearerTokenFilter(token, mapper);
    for (String value : new String[] {"Bearer ", "Bearer", "bearer " + token.reveal(), "Bearer  " + token.reveal(), "Bearer " + token.reveal() + " ", "Token " + token.reveal()}) {
      Outcome outcome = run(filter, request("GET", "/api/v1/health", value));
      assertThat(outcome.status()).as(value).isEqualTo(401);
      assertThat(outcome.passed()).isFalse();
      assertThat(outcome.authenticate()).isEqualTo("Bearer");
      assertThat(outcome.body()).isEqualTo("{\"code\":\"WRITE_TOKEN_INVALID\",\"status\":401,\"title\":\"Write token invalid\"}\n");
    }
  }

  @Test
  void anErrorDispatchIsCheckedForTheTokenToo() throws Exception {
    BearerTokenFilter filter = new BearerTokenFilter(token, mapper);
    MockHttpServletRequest error = request("TRACE", "/api/v1/health", null);
    error.setDispatcherType(DispatcherType.ERROR);
    error.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/health");
    Outcome outcome = run(filter, error);
    assertThat(outcome.status()).isEqualTo(401);
    assertThat(outcome.passed()).isFalse();

    MockHttpServletRequest accepted = request("GET", "/api/v1/x", "Bearer " + token.reveal());
    accepted.setDispatcherType(DispatcherType.ERROR);
    accepted.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/x");
    assertThat(run(filter, accepted).passed()).isTrue();
  }

  @Test
  void theProtocolCheckCoversTheShutdownAndTheWritesOnly() throws Exception {
    ProtocolHeaderFilter filter = new ProtocolHeaderFilter(mapper);
    for (String path : new String[] {"/api/v1/handshake", "/api/v1/health", "/api/v1/nowhere", "/api/v1/missions/X/other"}) {
      assertThat(run(filter, request("POST", path, null)).passed()).as(path).isTrue();
    }
    for (String path : new String[] {"/api/v1/shutdown", "/api/v1/missions/X/work-package-transitions", "/api/v1/missions/X/work-package-transitions/batch", "/api/v1/missions/X/inner-state-annotations"}) {
      Outcome outcome = run(filter, request("POST", path, null));
      assertThat(outcome.status()).as(path).isEqualTo(400);
      assertThat(outcome.body()).contains("\"code\":\"PROTOCOL_VERSION_UNSUPPORTED\"").contains("Supported protocol versions: 1.");
      MockHttpServletRequest withHeader = request("POST", path, null);
      withHeader.addHeader(ProtocolHeaderFilter.HEADER, "1");
      assertThat(run(filter, withHeader).passed()).as(path + " with protocol 1").isTrue();
    }
  }
}
