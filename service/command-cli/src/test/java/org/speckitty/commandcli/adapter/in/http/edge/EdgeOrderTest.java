package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract's order of checks (contracts/mission-write openapi.yaml): the Host and Origin guard (403), the bearer token (401), the
 * protocol header (400), the size cap (413). A request failing several checks gets the FIRST one's refusal; each refusal is the
 * WriteRefusal with its code and the status the code fixes, and no refusal echoes the token or a header value.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeOrderTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String WRITE = "/api/v1/missions/01M694HJ0000000000000000AA/work-package-transitions";

  private EdgeRig server;

  @BeforeAll
  void start() {
    server = EdgeRig.start();
  }

  @AfterAll
  void stop() {
    server.close();
  }

  /** One request: which checks it fails (guard, token, protocol, size) and the refusal the first failure gives. */
  private record Case(String name, boolean badHost, boolean badToken, boolean badProtocol, boolean oversized, int status, String code) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    for (int mask = 1; mask < 16; mask++) {
      boolean host = (mask & 1) != 0;
      boolean token = (mask & 2) != 0;
      boolean protocol = (mask & 4) != 0;
      boolean size = (mask & 8) != 0;
      int status;
      String code;
      if (host) {
        status = 403;
        code = "REQUEST_REFUSED";
      } else if (token) {
        status = 401;
        code = "WRITE_TOKEN_INVALID";
      } else if (protocol) {
        status = 400;
        code = "PROTOCOL_VERSION_UNSUPPORTED";
      } else {
        status = 413;
        code = "REQUEST_TOO_LARGE";
      }
      String name = (host ? "host " : "") + (token ? "token " : "") + (protocol ? "protocol " : "") + (size ? "size" : "");
      cases.add(Arguments.of(new Case(name.strip(), host, token, protocol, size, status, code)));
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "fails {0}")
  @MethodSource("cases")
  void theFirstFailingCheckDecides(Case c) {
    List<String> headers = new ArrayList<>();
    headers.add(c.badHost() ? "Host: evil.example:" + server.port() : server.host());
    headers.add(c.badToken() ? "Authorization: Bearer not-the-token" : server.bearer());
    headers.add(c.badProtocol() ? "Spec-Kitty-Write-Protocol: 2" : "Spec-Kitty-Write-Protocol: 1");
    headers.add("Content-Type: application/json");
    String body = c.oversized() ? "{\"pad\":\"" + "x".repeat(BodySizeFilter.MAX_BYTES) + "\"}" : "{}";

    RawClient.Reply reply = server.request("POST " + WRITE + " HTTP/1.1", body, headers.toArray(String[]::new));

    assertThat(reply.status()).isEqualTo(c.status());
    JsonNode problem = JSON.readTree(reply.body());
    assertThat(problem.get("code").asString()).isEqualTo(c.code());
    assertThat(problem.get("status").asInt()).isEqualTo(c.status());
    assertThat(reply.head()).containsIgnoringCase("Content-Type: application/problem+json");
    assertThat(reply.text()).doesNotContain(server.token.reveal()).doesNotContain("not-the-token").doesNotContain("evil.example");
    if (c.status() == 401) {
      assertThat(reply.head()).contains("WWW-Authenticate: Bearer");
    }
  }

  static Stream<Arguments> badTokens() {
    return Stream.of(
        Arguments.of("no Authorization", new String[] {}),
        Arguments.of("wrong token", new String[] {"Authorization: Bearer wrong"}),
        Arguments.of("lower-case scheme", new String[] {"Authorization: bearer %s"}),
        Arguments.of("Basic scheme", new String[] {"Authorization: Basic %s"}),
        Arguments.of("two spaces", new String[] {"Authorization: Bearer  %s"}),
        Arguments.of("token with suffix", new String[] {"Authorization: Bearer %sx"}),
        Arguments.of("two headers", new String[] {"Authorization: Bearer %s", "Authorization: Bearer %s"}),
        Arguments.of("scheme without a token (Tomcat trims the space)", new String[] {"Authorization: Bearer "}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badTokens")
  void everyOperationAndUnknownPathNeedsTheToken(String name, String[] tokenHeaders) {
    for (String target : List.of("GET /api/v1/handshake", "GET /api/v1/health", "POST /api/v1/shutdown", "POST " + WRITE, "GET /api/v1/nowhere-at-all")) {
      List<String> headers = new ArrayList<>(List.of(server.host(), "Spec-Kitty-Write-Protocol: 1"));
      for (String header : tokenHeaders) {
        headers.add(header.formatted(server.token.reveal()));
      }
      RawClient.Reply reply = server.request(target + " HTTP/1.1", "", headers.toArray(String[]::new));
      assertThat(reply.status()).as(target).isEqualTo(401);
      assertThat(reply.head()).contains("WWW-Authenticate: Bearer");
      assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo("WRITE_TOKEN_INVALID");
    }
  }

  static Stream<String> badProtocols() {
    return Stream.of("", "0", "2", "01", "+1", "1.0", "one", "1, 1");
  }

  @ParameterizedTest(name = "protocol ''{0}''")
  @MethodSource("badProtocols")
  void theShutdownAndTheWritesNeedASupportedProtocol(String value) {
    for (String target : List.of("POST /api/v1/shutdown", "POST " + WRITE, "POST /api/v1/missions/01M694HJ0000000000000000AA/work-package-transitions/batch",
        "POST /api/v1/missions/01M694HJ0000000000000000AA/inner-state-annotations")) {
      RawClient.Reply reply = server.request(target + " HTTP/1.1", "", server.host(), server.bearer(), "Spec-Kitty-Write-Protocol: " + value);
      assertThat(reply.status()).as(target).isEqualTo(400);
      JsonNode problem = JSON.readTree(reply.body());
      assertThat(problem.get("code").asString()).isEqualTo("PROTOCOL_VERSION_UNSUPPORTED");
      assertThat(problem.get("detail").asString()).isEqualTo("Supported protocol versions: 1.");
    }
  }

  @Test
  void aMissingOrRepeatedProtocolHeaderIsRefused() {
    RawClient.Reply missing = server.request("POST /api/v1/shutdown HTTP/1.1", "", server.host(), server.bearer());
    RawClient.Reply twice =
        server.request("POST /api/v1/shutdown HTTP/1.1", "", server.host(), server.bearer(), "Spec-Kitty-Write-Protocol: 1", "Spec-Kitty-Write-Protocol: 1");
    assertThat(missing.status()).isEqualTo(400);
    assertThat(twice.status()).isEqualTo(400);
  }

  @Test
  void theHandshakeAndTheHealthNeedNoProtocolHeader() {
    for (String target : List.of("GET /api/v1/handshake", "GET /api/v1/health")) {
      RawClient.Reply reply = server.request(target + " HTTP/1.1", "", server.host(), server.bearer());
      assertThat(reply.status()).as(target).isNotEqualTo(400).isNotEqualTo(401).isNotEqualTo(403);
    }
  }

  @Test
  void aChunkedBodyOverTheCapIsRefusedWithoutADeclaredLength() {
    StringBuilder request =
        new StringBuilder("POST " + WRITE + " HTTP/1.1\r\n")
            .append(server.host())
            .append("\r\n")
            .append(server.bearer())
            .append("\r\nSpec-Kitty-Write-Protocol: 1\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
    int chunk = 64 * 1024;
    for (int sent = 0; sent <= BodySizeFilter.MAX_BYTES; sent += chunk) {
      request.append(Integer.toHexString(chunk)).append("\r\n").append("x".repeat(chunk)).append("\r\n");
    }
    request.append("0\r\n\r\n");

    RawClient.Reply reply = RawClient.send(server.port(), request.toString());

    assertThat(reply.status()).isEqualTo(413);
    assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo("REQUEST_TOO_LARGE");
  }

  @Test
  void anUnknownPathWithTheTokenIsANotFoundProblemWithoutACode() {
    RawClient.Reply reply = server.request("GET /api/v1/nowhere-at-all HTTP/1.1", "", server.host(), server.bearer());
    assertThat(reply.status()).isEqualTo(404);
    assertThat(reply.head()).containsIgnoringCase("Content-Type: application/problem+json");
    JsonNode problem = JSON.readTree(reply.body());
    assertThat(problem.get("status").asInt()).isEqualTo(404);
    assertThat(problem.has("code")).isFalse();
    assertThat(reply.text()).doesNotContain("nowhere-at-all").doesNotContain("timestamp");
  }
}
