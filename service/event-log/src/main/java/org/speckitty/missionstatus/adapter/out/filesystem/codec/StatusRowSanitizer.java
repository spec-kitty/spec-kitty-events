package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.speckitty.missionstatus.domain.model.PyIsoDateTime;

/** Pure deep copy of core's PII stripping and top-level session-time replacement. */
public final class StatusRowSanitizer {

  private static final Set<String> PII_FIELDS =
      Set.of("machine_name", "hostname", "workspace_path", "developer_name", "developer_email");

  private StatusRowSanitizer() {}

  public static Map<String, Object> sanitize(Map<String, Object> row) {
    Map<String, Object> copy = stripMapping(row);
    Object startedRaw = copy.remove("session_started_at");
    Object endedRaw = copy.remove("session_ended_at");
    PyIsoDateTime started = parseSessionTime(startedRaw);
    PyIsoDateTime ended = parseSessionTime(endedRaw);
    if (started != null && ended != null) {
      replaceSessionDuration(copy, started, ended);
    }
    return copy;
  }

  /** Applies both protocol refusals without producing an encoded row. */
  public static void check(Map<String, Object> row) {
    requireFinite(sanitize(row));
  }

  private static Map<String, Object> stripMapping(Map<?, ?> mapping) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : mapping.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("JSON object keys must be strings");
      }
      if (!PII_FIELDS.contains(key)) {
        copy.put(key, stripValue(entry.getValue()));
      }
    }
    return copy;
  }

  private static Object stripValue(Object value) {
    if (value instanceof Map<?, ?> mapping) {
      return stripMapping(mapping);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      for (Object item : list) {
        copy.add(stripValue(item));
      }
      return copy;
    }
    return value;
  }

  private static PyIsoDateTime parseSessionTime(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    return PyIsoDateTime.parse(text).orElse(null);
  }

  private static void replaceSessionDuration(Map<String, Object> copy, PyIsoDateTime started, PyIsoDateTime ended) {
    if (started.aware() != ended.aware()) {
      throw new StatusRowRefusedException(StatusRowRefusedException.Kind.SESSION_TIMES_NOT_SUBTRACTABLE);
    }
    copy.put("session_duration_s", BigInteger.valueOf(PyIsoDateTime.truncatedSecondsBetween(started, ended)));
  }

  private static void requireFinite(Object value) {
    if (value instanceof Double number && !Double.isFinite(number)) {
      throw new StatusRowRefusedException(StatusRowRefusedException.Kind.NON_FINITE);
    }
    if (value instanceof Map<?, ?> mapping) {
      mapping.values().forEach(StatusRowSanitizer::requireFinite);
    } else if (value instanceof List<?> list) {
      list.forEach(StatusRowSanitizer::requireFinite);
    }
  }
}
