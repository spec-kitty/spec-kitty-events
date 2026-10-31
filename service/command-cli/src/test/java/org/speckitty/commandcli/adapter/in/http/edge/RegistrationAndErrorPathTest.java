package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/**
 * Where the edge checks sit and what the container's own errors look like, copied from the read service's RegistrationTest,
 * DirectErrorPathTest and RequestEchoTest. Every request meets the filters before the dispatcher, so their order is pinned on the
 * registrations: the guard first at the highest precedence, then the token, both on the REQUEST and the ERROR dispatch; then the protocol
 * header and the size cap on the REQUEST dispatch. No reply and no log line echoes text the caller controls.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class RegistrationAndErrorPathTest {

  private static final String HOME_PATH = "/" + String.join("/", "ho" + "me", "canary-user", "URLCANARY");
  private static final String MAIL = "mail-canary@example.invalid";

  private EdgeRig server;

  @BeforeAll
  void start() {
    server = EdgeRig.start();
  }

  @AfterAll
  void stop() {
    server.close();
  }

  @Test
  @SuppressWarnings("rawtypes")
  void theEdgeChecksAreRegisteredInTheContractsOrderOnEveryPath() {
    Map<String, FilterRegistrationBean> registrations = server.context().getBeansOfType(FilterRegistrationBean.class);
    FilterRegistrationBean guard = registrations.get("hostOriginFilter");
    FilterRegistrationBean token = registrations.get("bearerTokenFilter");
    FilterRegistrationBean canonical = registrations.get("canonicalPathFilter");
    FilterRegistrationBean protocol = registrations.get("protocolHeaderFilter");
    FilterRegistrationBean size = registrations.get("bodySizeFilter");

    assertThat(guard.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(token.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    assertThat(canonical.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
    assertThat(protocol.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 3);
    assertThat(size.getOrder()).as("after the activity count (+4)").isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5);
    assertThat(registrations.get("activityFilter").getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 4);
    for (FilterRegistrationBean registration : new FilterRegistrationBean[] {guard, token, canonical, protocol, size}) {
      assertThat(registration.getUrlPatterns()).containsExactly("/*");
      assertThat(registration.isEnabled()).isTrue();
    }
    assertThat(guard.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));
    assertThat(token.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));
    assertThat(canonical.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST));
    assertThat(protocol.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST));
    assertThat(size.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST));
    assertThat(registrations.values()).as("no other registration precedes the guard").allSatisfy(other -> assertThat(other.getOrder()).isGreaterThanOrEqualTo(guard.getOrder()));
  }

  @Test
  void theOwnPortIsTheBoundPortOfTheServer() {
    assertThat(server.context().getBean(OwnPortSource.class).port()).isEqualTo(server.port()).isPositive();
  }

  @Test
  void aDirectRequestForTheErrorPageIsA404Problem() {
    for (String route : new String[] {"/error", "/error?x=1"}) {
      RawClient.Reply reply = server.request("GET " + route + " HTTP/1.1", "", server.host(), server.bearer());
      assertThat(reply.status()).as(route).isEqualTo(404);
      assertThat(reply.head()).containsIgnoringCase("Content-Type: application/problem+json");
      assertThat(reply.body()).contains("\"status\":404").doesNotContain("x=1");
    }
  }

  @Test
  void aContainerRoutedErrorKeepsItsStatusAndEchoesNoPath() {
    RawClient.Reply trace = server.request("TRACE " + HOME_PATH + " HTTP/1.1", "", server.host(), server.bearer());
    assertThat(trace.status()).isEqualTo(405);
    assertThat(trace.head()).containsIgnoringCase("Content-Type: application/problem+json");
    assertThat(trace.body().strip()).isEqualTo("{\"status\":405,\"title\":\"Method Not Allowed\"}");
    assertThat(trace.text()).doesNotContain("URLCANARY").doesNotContain("\"path\"").doesNotContain("timestamp");
  }

  @Test
  void aContainerRoutedErrorWithoutTheTokenIsRefusedOnItsErrorDispatch() {
    // the REQUEST dispatch of a container-refused method never reaches the filters' REQUEST pass for TRACE; the ERROR pass decides
    RawClient.Reply trace = server.request("TRACE /api/v1/health HTTP/1.1", "", server.host());
    assertThat(trace.status()).isEqualTo(401);
    assertThat(trace.body()).contains("\"code\":\"WRITE_TOKEN_INVALID\"");
  }

  @Test
  void aBadlyEncodedTargetIsTheContainersOwn400AndEchoesNothing() {
    RawClient.Reply reply = server.request("GET /api/v1/health?x=%zz HTTP/1.1", "", "Host: evil.example:" + server.port());
    RawClient.Reply path = server.request("GET /%zz HTTP/1.1", "", "Host: evil.example:" + server.port());
    for (RawClient.Reply r : new RawClient.Reply[] {reply, path}) {
      assertThat(r.status()).isIn(400, 403);
      assertThat(r.text()).doesNotContain("evil.example").doesNotContain("%zz");
    }
  }

  @Test
  void noLogLineEchoesAPathAnAddressOrAHeaderTheCallerSent(CapturedOutput output) {
    int port = server.port();
    server.request("GET " + HOME_PATH + " HTTP/1.1", "", server.host(), server.bearer());
    server.request("GET /api/v1/../api/v1/health HTTP/1.1", "", server.host(), server.bearer());
    server.request("GET /" + MAIL + " HTTP/1.1", "", server.host(), server.bearer());
    server.request("GET /api/v1/health HTTP/1.1", "", "Host: " + MAIL + HOME_PATH);
    server.request("GET /api/v1/health HTTP/1.1", "", "Host: localhost:" + port + ".evil.example/" + MAIL);
    server.request("GET /api/v1/health HTTP/1.1", "", server.host(), server.bearer(), "Cookie: token=" + MAIL + "\"unterminated; a=b\\");
    server.request("GET /api/v1/health HTTP/1.1", "", server.host(), "Authorization: Bearer " + MAIL);

    assertThat(output.getAll())
        .doesNotContain("URLCANARY")
        .doesNotContain("canary-user")
        .doesNotContain("mail-canary")
        .doesNotContain("evil.example")
        .doesNotContain("api/v1/../api/v1")
        .doesNotContain(server.token.reveal());
  }
}
