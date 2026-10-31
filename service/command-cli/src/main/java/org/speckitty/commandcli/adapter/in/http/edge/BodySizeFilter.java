package org.speckitty.commandcli.adapter.in.http.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The size cap, fourth in the contract's order: a write body over {@link #MAX_BYTES} (1 MiB, provisional in the contract) is 413
 * {@code REQUEST_TOO_LARGE}. The filter reads at most one byte past the cap before any controller runs, so a declared length and a chunked
 * body are judged alike and an oversized body is never parsed; a body within the cap is handed on as the bytes read.
 */
public class BodySizeFilter extends OncePerRequestFilter {

  public static final int MAX_BYTES = 1024 * 1024;

  private final JsonMapper mapper;

  public BodySizeFilter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !Operations.isWrite(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    long declared = request.getContentLengthLong();
    if (declared > MAX_BYTES) {
      RefusalBody.of(RefusalCode.REQUEST_TOO_LARGE).write(response, mapper);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
    if (body.length > MAX_BYTES) {
      RefusalBody.of(RefusalCode.REQUEST_TOO_LARGE).write(response, mapper);
      return;
    }
    chain.doFilter(new BufferedBody(request, body), response);
  }

  /** The request with its body replaced by the bytes already read. */
  static final class BufferedBody extends HttpServletRequestWrapper {

    private final byte[] body;

    BufferedBody(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("the body is already buffered");
        }

        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          return in.read(buffer, offset, length);
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
