package org.speckitty.commandcli.domain.authority;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** File/File JSON reader for WP04's parity corpus; test-classpath Jackson never enters the authority. */
final class CorpusCases {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CORPUS = "src/spec_kitty_events/conformance/status_transition_parity/cases";

  private CorpusCases() {}

  record CorpusCase(String id, Map<String, Object> value) {
    String text(String key) {
      return (String) value.get(key);
    }

    String area() {
      return text("area");
    }

    String oracle() {
      return text("oracle");
    }

    boolean domain() {
      return "domain".equals(text("layer"));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> request() {
      return (Map<String, Object>) value.get("request");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> units() {
      return (List<Map<String, Object>>) request().get("units");
    }

    FoldView fold() {
      if (value.get("log_text") instanceof String text) {
        return WriteFold.fold(logText(text));
      }
      List<LogLine> lines = new ArrayList<>();
      for (Object row : list("log")) {
        lines.add(new LogLine.Value(castMap(row)));
      }
      return WriteFold.fold(lines);
    }

    MissionFacts facts() {
      Map<String, String> tasks = new LinkedHashMap<>();
      castMap(value.get("tasks")).forEach((name, text) -> tasks.put(name, (String) text));
      return new CorpusMissionFacts(tasks);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> expected() {
      return (Map<String, Object>) value.get("expected");
    }

    private List<Object> list(String key) {
      Object result = value.get(key);
      return result instanceof List<?> values ? values.stream().map(Object.class::cast).toList() : List.of();
    }

    private List<LogLine> logText(String text) {
      List<LogLine> lines = new ArrayList<>();
      for (String raw : text.split("\\n", -1)) {
        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        if (line.isBlank()) {
          lines.add(new LogLine.Blank());
          continue;
        }
        try {
          lines.add(new LogLine.Value(castMap(valueTree(JSON.readTree(line)))));
        } catch (RuntimeException exception) {
          lines.add(new LogLine.Unparseable());
        }
      }
      return lines;
    }
  }

  static List<CorpusCase> load() {
    Path root = corpusRoot();
    try (Stream<Path> paths = Files.list(root)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .sorted()
          .map(CorpusCases::read)
          .toList();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read transition parity corpus", exception);
    }
  }

  private static CorpusCase read(Path path) {
    try {
      Map<String, Object> value = castMap(valueTree(JSON.readTree(Files.readString(path))));
      String id = path.getFileName().toString().replaceFirst("\\.json$", "");
      return new CorpusCase(id, value);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Cannot read " + path, exception);
    }
  }

  private static Path corpusRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      Path candidate = current.resolve(CORPUS);
      if (Files.isRegularFile(current.resolve("pyproject.toml")) && Files.isDirectory(candidate)) {
        try (Stream<Path> paths = Files.list(candidate)) {
          long count = paths.filter(path -> path.toString().endsWith(".json")).count();
          if (count < 150) {
            throw new IllegalStateException("Transition parity corpus has only " + count + " cases");
          }
        } catch (IOException exception) {
          throw new IllegalStateException("Cannot inspect transition parity corpus", exception);
        }
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Transition parity corpus not found from " + System.getProperty("user.dir"));
  }

  private static Object valueTree(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isObject()) {
      Map<String, Object> result = new LinkedHashMap<>();
      node.properties().forEach(entry -> result.put(entry.getKey(), valueTree(entry.getValue())));
      return result;
    }
    if (node.isArray()) {
      List<Object> result = new ArrayList<>();
      node.forEach(item -> result.add(valueTree(item)));
      return result;
    }
    if (node.isIntegralNumber()) return node.bigIntegerValue();
    if (node.isFloatingPointNumber()) return node.doubleValue();
    if (node.isBoolean()) return node.booleanValue();
    return node.stringValue();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }
}
