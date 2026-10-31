package org.speckitty.commandcli.adapter.in.http.edge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A raw-socket client that sends literal request bytes and reads until the server closes (copied from the read service's test support):
 * the JDK client sets {@code Host} itself, so a foreign Host can only be sent this way.
 */
public final class RawClient {

  /** The reply: the status line's code (0 when none) and the full text. */
  public record Reply(int status, String text) {

    /** The headers, up to the blank line. */
    public String head() {
      int split = text.indexOf("\r\n\r\n");
      return split < 0 ? text : text.substring(0, split);
    }

    /** The body after the blank line, with a chunked transfer coding removed. */
    public String body() {
      int split = text.indexOf("\r\n\r\n");
      if (split < 0) {
        return "";
      }
      String raw = text.substring(split + 4);
      if (!head().toLowerCase().contains("transfer-encoding: chunked")) {
        return raw;
      }
      StringBuilder out = new StringBuilder();
      int at = 0;
      while (at < raw.length()) {
        int eol = raw.indexOf("\r\n", at);
        int size = Integer.parseInt(raw.substring(at, eol).strip(), 16);
        if (size == 0) {
          break;
        }
        out.append(raw, eol + 2, eol + 2 + size);
        at = eol + 2 + size + 2;
      }
      return out.toString();
    }
  }

  private RawClient() {}

  /** Sends {@code requestLine}, the header lines, {@code Connection: close}, a blank line and {@code body} (may be empty). */
  public static Reply request(int port, String requestLine, String body, String... headerLines) {
    StringBuilder text = new StringBuilder(requestLine).append("\r\n");
    for (String line : headerLines) {
      text.append(line).append("\r\n");
    }
    if (!body.isEmpty()) {
      text.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
    }
    text.append("Connection: close\r\n\r\n").append(body);
    return send(port, text.toString());
  }

  public static Reply send(int port, String requestText) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
      socket.setSoTimeout(10000);
      OutputStream out = socket.getOutputStream();
      out.write(requestText.getBytes(StandardCharsets.UTF_8));
      out.flush();
      ByteArrayOutputStream collected = new ByteArrayOutputStream();
      InputStream in = socket.getInputStream();
      byte[] buffer = new byte[4096];
      int read;
      try {
        while ((read = in.read(buffer)) >= 0) {
          collected.write(buffer, 0, read);
        }
      } catch (java.net.SocketTimeoutException timeout) {
        // return what arrived
      } catch (java.net.SocketException reset) {
        // the server may close after answering an oversized body
      }
      String text = collected.toString(StandardCharsets.UTF_8);
      int status = 0;
      if (text.startsWith("HTTP/") && text.length() > 12) {
        status = Integer.parseInt(text.substring(9, 12));
      }
      return new Reply(status, text);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
