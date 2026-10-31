package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The guard's decision in isolation (a fixed own port), copied from the read service's HostOriginFilterTest: values a real client cannot
 * send through the container, the port-unknown refusal, several Origin values, the refusal body, and the ERROR dispatch, which the
 * real-socket tables cannot reach (the REQUEST dispatch refuses first).
 */
@Tag("native")
class HostOriginFilterTest {

  private static final int PORT = 43210;

  private final HostOriginFilter filter = new HostOriginFilter(() -> PORT, JsonMapper.builder().build());

  private record Outcome(int status, boolean passed, String body, String contentType) {}

  private Outcome run(MockHttpServletRequest request) throws ServletException, IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicInteger passed = new AtomicInteger();
    filter.doFilter(request, response, new MockFilterChain() {
      @Override
      public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
        passed.incrementAndGet();
      }
    });
    return new Outcome(response.getStatus(), passed.get() == 1, response.getContentAsString(StandardCharsets.UTF_8), response.getContentType());
  }

  private Outcome run(String host, String origin, String... extraHeaders) throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
    if (host != null) {
      request.addHeader("Host", host);
    }
    if (origin != null) {
      request.addHeader("Origin", origin);
    }
    for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
      request.addHeader(extraHeaders[i], extraHeaders[i + 1]);
    }
    return run(request);
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1:43210", "localhost:43210"})
  void theTwoAcceptedHostFormsPassWithAndWithoutAnAcceptedOrigin(String host) throws Exception {
    assertThat(run(host, null).passed()).isTrue();
    assertThat(run(host, "http://127.0.0.1:43210").passed()).isTrue();
    assertThat(run(host, "http://localhost:43210").passed()).isTrue();
    assertThat(run(host, null, "Sec-Fetch-Site", "same-origin").passed()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1:43211", "127.0.0.1:4321", "127.0.0.1:043210", "127.0.0.1:43210 ", " 127.0.0.1:43210", "127.0.0.1:43210:1", "localhost:+43210", "[::1]:43210", "0.0.0.0:43210", "127.0.0.2:43210", "LOCALHOST:43210", "Localhost:43210", "localhost.:43210", "127.0.0.1.:43210", "", "evil.example"})
  void everyOtherHostIsRefusedWithoutReachingTheChain(String host) throws Exception {
    Outcome outcome = run(host, null);
    assertThat(outcome.status()).isEqualTo(403);
    assertThat(outcome.passed()).isFalse();
  }

  @Test
  void anAbsentHostIsRefused() throws Exception {
    assertThat(run(null, null).status()).isEqualTo(403);
  }

  @Test
  void theProxyHeadersAndACrossSiteFetchAreRefusedEvenWithAnAcceptedHost() throws Exception {
    assertThat(run("127.0.0.1:43210", null, "Forwarded", "for=1.2.3.4").status()).isEqualTo(403);
    assertThat(run("127.0.0.1:43210", null, "X-Forwarded-Host", "127.0.0.1:43210").status()).isEqualTo(403);
    assertThat(run("127.0.0.1:43210", null, "X-Forwarded-Host", "").status()).isEqualTo(403);
    assertThat(run("127.0.0.1:43210", null, "Sec-Fetch-Site", "cross-site").status()).isEqualTo(403);
    assertThat(run("127.0.0.1:43210", null, "Sec-Fetch-Site", "CROSS-SITE").status()).isEqualTo(403);
  }

  @Test
  void aSecondOriginValueThatIsForeignIsRefused() throws Exception {
    Outcome outcome = run("127.0.0.1:43210", "http://127.0.0.1:43210", "Origin", "https://evil.example");
    assertThat(outcome.status()).isEqualTo(403);
    assertThat(outcome.passed()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "", "https://127.0.0.1:43210", "http://127.0.0.1:43211", "http://127.0.0.1", "http://127.0.0.1:43210/", "http://127.0.0.1:43210/x", "http://127.0.0.1.evil.example:43210", "http://user@127.0.0.1:43210", "HTTP://127.0.0.1:43210", "http://LOCALHOST:43210", "http://[::1]:43210", "file://"})
  void everyOtherOriginIsRefused(String origin) throws Exception {
    Outcome outcome = run("127.0.0.1:43210", origin);
    assertThat(outcome.status()).isEqualTo(403);
    assertThat(outcome.passed()).isFalse();
  }

  @Test
  void aPortNotYetKnownRefusesEverything() throws Exception {
    HostOriginFilter beforeBind = new HostOriginFilter(() -> -1, JsonMapper.builder().build());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
    request.addHeader("Host", "127.0.0.1:-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicInteger passed = new AtomicInteger();
    beforeBind.doFilter(request, response, new MockFilterChain() {
      @Override
      public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
        passed.incrementAndGet();
      }
    });
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(passed.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"evil.example\" onload=\"x", "<script>alert(1)</script>.example:43210", "café-marker.example", "evil\u0001marker.example", "marker.example\\u0000"})
  void aRefusalCarriesNeitherTheHostNorTheOriginBackInAnyForm(String foreign) throws Exception {
    for (Outcome outcome : List.of(run(foreign, null), run("127.0.0.1:43210", foreign), run("127.0.0.1:43210", null, "X-Forwarded-Host", foreign))) {
      assertThat(outcome.status()).isEqualTo(403);
      assertThat(outcome.body()).doesNotContain("marker").doesNotContain("evil").doesNotContain("script").doesNotContain("onload").doesNotContain("caf");
      assertThat(outcome.contentType()).startsWith("application/problem+json");
    }
  }

  @Test
  void theBodyIsTheWriteRefusalWithOneTrailingNewline() throws Exception {
    assertThat(run("evil.example", null).body()).isEqualTo("{\"code\":\"REQUEST_REFUSED\",\"status\":403,\"title\":\"Request refused\"}\n");
  }

  @Test
  void anErrorDispatchIsGuardedAndARequestThatAlreadyPassedPassesItsErrorRedispatch() throws Exception {
    MockHttpServletRequest foreign = new MockHttpServletRequest("TRACE", "/api/v1/health");
    foreign.setDispatcherType(DispatcherType.ERROR);
    foreign.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/health");
    foreign.addHeader("Host", "evil.example");
    Outcome refused = run(foreign);
    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.passed()).isFalse();

    MockHttpServletRequest accepted = new MockHttpServletRequest("GET", "/api/v1/x");
    accepted.addHeader("Host", "127.0.0.1:43210");
    assertThat(run(accepted).passed()).isTrue();
    accepted.setDispatcherType(DispatcherType.ERROR);
    accepted.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/x");
    Outcome redispatch = run(accepted);
    assertThat(redispatch.passed()).isTrue();
    assertThat(redispatch.status()).isEqualTo(200);
  }
}
