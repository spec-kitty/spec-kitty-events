package org.speckitty.commandcli.adapter.in.http.edge;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * The body of every refusal: the contract's WriteRefusal (the shared Problem with a {@code code} and the status that code fixes), written as
 * compact JSON and one newline, with only the members that are set. It never carries the token, a host path, a request header or row
 * content; {@code detail} and {@code instance} are fixed texts the service chose.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefusalBody(String code, String detail, String instance, int status, String title) {

  public static RefusalBody of(RefusalCode code) {
    return new RefusalBody(code.name(), null, null, code.status(), code.title());
  }

  public static RefusalBody of(RefusalCode code, String detail) {
    return new RefusalBody(code.name(), detail, null, code.status(), code.title());
  }

  public RefusalBody withInstance(String pointer) {
    return new RefusalBody(code, detail, pointer, status, title);
  }

  /** The canonical bytes: compact members, then exactly one newline. */
  public byte[] bytes(JsonMapper mapper) {
    return (mapper.writeValueAsString(this) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** Writes this refusal as the whole response (filters answer before any controller runs). */
  public void write(HttpServletResponse response, JsonMapper mapper) throws IOException {
    byte[] body = bytes(mapper);
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
    response.flushBuffer();
  }
}
