package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The 1 MiB cap at its boundary, on a declared length and on the bytes actually sent, and the body the next handler reads. */
@Tag("native")
class BodySizeFilterTest {

  private static final String WRITE = "/api/v1/missions/01J6XW9KQT7M0YB3N4R5CQZ2EX/work-package-transitions";

  private final BodySizeFilter filter = new BodySizeFilter(JsonMapper.builder().build());

  private record Outcome(int status, byte[] handed, String body) {}

  private Outcome run(String path, byte[] body, Long declaredLength) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
      @Override
      public long getContentLengthLong() {
        return declaredLength == null ? -1 : declaredLength;
      }

      @Override
      public int getContentLength() {
        return declaredLength == null ? -1 : (int) Math.min(Integer.MAX_VALUE, declaredLength);
      }
    };
    request.setContent(body);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<byte[]> handed = new AtomicReference<>();
    filter.doFilter(request, response, new MockFilterChain() {
      @Override
      public void doFilter(ServletRequest req, ServletResponse res) throws IOException {
        handed.set(req.getInputStream().readAllBytes());
        assertThat(req.getContentLengthLong()).isEqualTo(handed.get().length);
      }
    });
    return new Outcome(response.getStatus(), handed.get(), response.getContentAsString());
  }

  private static byte[] bytes(int length) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) ('a' + i % 26);
    }
    return bytes;
  }

  @Test
  void aBodyOfExactlyTheCapIsHandedOnByteForByteWithOrWithoutADeclaredLength() throws Exception {
    byte[] body = bytes(BodySizeFilter.MAX_BYTES);
    for (Long declared : new Long[] {(long) BodySizeFilter.MAX_BYTES, null}) {
      Outcome outcome = run(WRITE, body, declared);
      assertThat(outcome.status()).isEqualTo(200);
      assertThat(Arrays.equals(outcome.handed(), body)).isTrue();
    }
  }

  @Test
  void oneByteOverTheCapIsRefusedWhenItIsReadWithoutADeclaredLength() throws Exception {
    Outcome outcome = run(WRITE, bytes(BodySizeFilter.MAX_BYTES + 1), null);
    assertThat(outcome.status()).isEqualTo(413);
    assertThat(outcome.handed()).isNull();
    assertThat(outcome.body()).isEqualTo("{\"code\":\"REQUEST_TOO_LARGE\",\"status\":413,\"title\":\"Request too large\"}\n");
  }

  @Test
  void aDeclaredLengthOverTheCapIsRefusedBeforeAnyByteIsRead() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", WRITE) {
      @Override
      public long getContentLengthLong() {
        return BodySizeFilter.MAX_BYTES + 1L;
      }

      @Override
      public jakarta.servlet.ServletInputStream getInputStream() {
        throw new AssertionError("the body must not be read");
      }
    };
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  void aSmallBodyAndAnEmptyBodyAreHandedOnAsSent() throws Exception {
    byte[] small = "{\"units\":[]}".getBytes();
    assertThat(run(WRITE, small, (long) small.length).handed()).isEqualTo(small);
    assertThat(run(WRITE, new byte[0], 0L).handed()).isEmpty();
  }

  @Test
  void everyPathIsCapped() throws Exception {
    for (String path : new String[] {"/api/v1/shutdown", "/api/v1/nowhere"}) {
      assertThat(run(path, bytes(BodySizeFilter.MAX_BYTES + 1), null).status()).as(path).isEqualTo(413);
      assertThat(run(path, bytes(10), 10L).handed()).hasSize(10);
    }
  }
}
