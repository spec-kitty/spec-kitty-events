package org.speckitty.commandcli.adapter.in.http.operations;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.speckitty.commandcli.application.write.WriteOutcome;
import tools.jackson.databind.json.JsonMapper;

/** Ordered-map durability acknowledgements, avoiding bean introspection in native images. */
public final class AckBody {
  private AckBody() {}

  public static byte[] bytes(WriteOutcome.Acked ack, JsonMapper mapper) {
    return (mapper.writeValueAsString(members(ack)) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  static Map<String, Object> members(WriteOutcome.Acked ack) {
    if (!ack.batch()) return unit(ack.units().getFirst());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fsyncs", ack.fsyncs());
    body.put("units", ack.units().stream().map(AckBody::unit).toList());
    return body;
  }

  private static Map<String, Object> unit(WriteOutcome.UnitAck ack) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("appendedLines", ack.appendedLines());
    body.put("eventIds", List.copyOf(ack.eventIds()));
    body.put("fsynced", true);
    body.put("replayed", ack.replayed());
    body.put("statusCommit", null);
    return body;
  }
}
