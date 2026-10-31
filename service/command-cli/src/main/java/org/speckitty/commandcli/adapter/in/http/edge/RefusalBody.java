package org.speckitty.commandcli.adapter.in.http.edge;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * The body of every refusal: the contract's WriteRefusal (the shared Problem with a {@code code} and the status that code fixes), written as
 * compact JSON and one newline, with only the members that are set. It never carries the token, a host path, a request header or row
 * content; {@code detail} and {@code instance} are fixed texts the service chose.
 *
 * <p>It is written as an ordered map, never by bean introspection of the record: the filters serialise it outside any controller, so
 * Spring's AOT never registers the record, and in the native image Jackson cannot read its components (the first native CI run of
 * #156 failed 46 native-tag tests that way). A map of strings and ints needs no reflection in either image.
 */
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
    return (mapper.writeValueAsString(members()) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** The members in the record's order, with only the ones that are set. */
  Map<String, Object> members() {
    Map<String, Object> members = new LinkedHashMap<>();
    members.put("code", code);
    if (detail != null) {
      members.put("detail", detail);
    }
    if (instance != null) {
      members.put("instance", instance);
    }
    members.put("status", status);
    members.put("title", title);
    return members;
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
