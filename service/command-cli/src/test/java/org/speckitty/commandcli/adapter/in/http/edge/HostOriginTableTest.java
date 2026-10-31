package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.IntFunction;
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
 * The Host and Origin tables of the read service's guard tests (HostTableTest, OriginTableTest), run against the write service on its
 * own targets (operator ruling of 2026-10-31: the guard is copied with its tests). A refusal is 403 {@code REQUEST_REFUSED} even with a
 * valid token (the guard is the first check), names no check and echoes no request value. The values the container itself refuses
 * before any filter keep the container's 400.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostOriginTableTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The write service's targets: the three Service operations, a write and an unknown path (a refusal, never a 404). */
  static final List<String> TARGETS =
      List.of("/api/v1/handshake", "/api/v1/health", "/api/v1/shutdown", "/api/v1/missions/01M694HJ0000000000000000AA/work-package-transitions", "/api/v1/nowhere-at-all");

  private record Row(String name, IntFunction<List<String>> headers, boolean absoluteForm, String http, int status, String foreignMarker) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static Row row(String name, IntFunction<List<String>> headers, int status, String marker) {
    return new Row(name, headers, false, "HTTP/1.1", status, marker);
  }

  private static final List<Row> HOSTS =
      List.of(
          row("1 evil.example", p -> List.of("Host: evil.example"), 403, "evil.example"),
          row("2 evil.example:port", p -> List.of("Host: evil.example:" + p), 403, "evil.example"),
          row("3 127.0.0.1 without port", p -> List.of("Host: 127.0.0.1"), 403, null),
          row("4 127.0.0.1:port+1", p -> List.of("Host: 127.0.0.1:" + (p + 1)), 403, null),
          new Row("5 no Host (HTTP/1.0)", p -> List.of(), false, "HTTP/1.0", 403, null),
          row("6 127.0.0.1.evil.example:port", p -> List.of("Host: 127.0.0.1.evil.example:" + p), 403, "evil.example"),
          row("7 localhost.evil.example:port", p -> List.of("Host: localhost.evil.example:" + p), 403, "evil.example"),
          row("9 [::1]:port over IPv4", p -> List.of("Host: [::1]:" + p), 403, "[::1]"),
          row("8 evil.example@127.0.0.1:port", p -> List.of("Host: evil.example@127.0.0.1:" + p), 400, "evil.example"),
          row("E1 duplicate Host", p -> List.of("Host: 127.0.0.1:" + p, "Host: 127.0.0.1:" + p), 400, null),
          new Row("E2 HTTP/1.1 without Host", p -> List.of(), false, "HTTP/1.1", 400, null),
          new Row("E3 HTTP/1.1 absolute-form foreign target", p -> List.of("Host: 127.0.0.1:" + p), true, "HTTP/1.1", 400, "evil.example"),
          new Row("E4 HTTP/1.0 absolute-form foreign target", p -> List.of(), true, "HTTP/1.0", 403, "evil.example"),
          row("E5 empty Host", p -> List.of("Host:"), 403, null),
          row("E6 accepted Host + X-Forwarded-Host", p -> List.of("Host: 127.0.0.1:" + p, "X-Forwarded-Host: evil.example"), 403, "evil.example"),
          row("E7 accepted Host + Forwarded", p -> List.of("Host: 127.0.0.1:" + p, "Forwarded: host=evil.example"), 403, "evil.example"),
          row("E8 LOCALHOST:port", p -> List.of("Host: LOCALHOST:" + p), 403, "LOCALHOST"),
          row("E9 localhost.:port", p -> List.of("Host: localhost.:" + p), 403, "localhost."),
          row("S1 Sec-Fetch-Site: cross-site", p -> List.of("Host: 127.0.0.1:" + p, "Sec-Fetch-Site: cross-site"), 403, null));

  private static final List<IntFunction<String>> ORIGINS =
      List.of(
          p -> "https://evil.example",
          p -> "null",
          p -> "http://127.0.0.1:" + (p + 1),
          p -> "http://127.0.0.1.evil.example:" + p,
          p -> "https://127.0.0.1:" + p,
          p -> "http://127.0.0.1",
          p -> "http://127.0.0.1:" + p + "/",
          p -> "HTTP://127.0.0.1:" + p,
          p -> "http://localhost:" + (p + 1),
          p -> "http://[::1]:" + p,
          p -> "");

  static Stream<Arguments> hostCases() {
    return HOSTS.stream().flatMap(row -> TARGETS.stream().map(target -> Arguments.of(row, target)));
  }

  static Stream<Arguments> originCases() {
    return Stream.iterate(0, i -> i + 1).limit(ORIGINS.size()).flatMap(i -> TARGETS.stream().map(target -> Arguments.of(i, target)));
  }

  private EdgeRig server;

  @BeforeAll
  void start() {
    server = EdgeRig.start();
  }

  @AfterAll
  void stop() {
    server.close();
  }

  @ParameterizedTest(name = "{0} on {1}")
  @MethodSource("hostCases")
  void everyHostRowAnswersItsLiteralStatusEvenWithAValidToken(Row row, String target) {
    int port = server.port();
    String requestTarget = row.absoluteForm() ? "http://evil.example" + target : target;
    List<String> headers = new java.util.ArrayList<>(row.headers().apply(port));
    headers.add(server.bearer());
    headers.add("Spec-Kitty-Write-Protocol: 1");
    RawClient.Reply reply = server.request("GET " + requestTarget + " " + row.http(), "", headers.toArray(String[]::new));

    assertThat(reply.status()).as("status").isEqualTo(row.status());
    assertThat(reply.text()).doesNotContain(server.token.reveal());
    if (row.foreignMarker() != null) {
      assertThat(reply.text()).as("no echo of the foreign value").doesNotContain(row.foreignMarker());
    }
    if (row.status() == 403) {
      assertRefusal(reply);
    } else {
      assertThat(reply.text()).doesNotContain("REQUEST_REFUSED");
    }
  }

  @ParameterizedTest(name = "Origin #{0} on {1}")
  @MethodSource("originCases")
  void aForeignOriginWithAnAcceptedHostIsRefused(int origin, String target) {
    int port = server.port();
    RawClient.Reply reply = server.request("GET " + target + " HTTP/1.1", "", server.host(), server.bearer(), "Origin: " + ORIGINS.get(origin).apply(port));

    assertThat(reply.status()).isEqualTo(403);
    assertRefusal(reply);
    assertThat(reply.text()).doesNotContainIgnoringCase("Access-Control-").doesNotContain("evil.example");
  }

  static Stream<String> acceptedOrigins() {
    return Stream.of("", "http://127.0.0.1:%d", "http://localhost:%d");
  }

  @ParameterizedTest(name = "accepted: Origin ''{0}'' with both Host forms")
  @MethodSource("acceptedOrigins")
  void theAcceptedFormsPassTheGuard(String origin) {
    int port = server.port();
    for (String host : List.of("Host: 127.0.0.1:" + port, "Host: localhost:" + port)) {
      String[] headers =
          origin.isEmpty() ? new String[] {host, server.bearer()} : new String[] {host, server.bearer(), "Origin: " + origin.formatted(port)};
      RawClient.Reply reply = server.request("GET /api/v1/nowhere-at-all HTTP/1.1", "", headers);
      assertThat(reply.status()).as(host + " " + origin).isEqualTo(404);
      assertThat(reply.text()).doesNotContain("REQUEST_REFUSED");
    }
  }

  @Test
  void aContainerRefusedMethodWithAForeignHostIsRefusedWithoutEcho() {
    RawClient.Reply reply = server.request("TRACE /api/v1/handshake HTTP/1.1", "", "Host: evil.example:" + server.port());
    assertThat(reply.status()).isEqualTo(403);
    assertRefusal(reply);
    assertThat(reply.text()).doesNotContain("evil.example").doesNotContain("timestamp");
  }

  private static void assertRefusal(RawClient.Reply reply) {
    assertThat(reply.head()).containsIgnoringCase("Content-Type: application/problem+json");
    String body = reply.body();
    assertThat(body).endsWith("\n");
    JsonNode problem = JSON.readTree(body);
    assertThat(problem.get("code").asString()).isEqualTo("REQUEST_REFUSED");
    assertThat(problem.get("title").asString()).isEqualTo("Request refused");
    assertThat(problem.get("status").asInt()).isEqualTo(403);
    assertThat(problem.has("detail")).as("no detail naming the failed check").isFalse();
    assertThat(problem.size()).isEqualTo(3);
  }
}
