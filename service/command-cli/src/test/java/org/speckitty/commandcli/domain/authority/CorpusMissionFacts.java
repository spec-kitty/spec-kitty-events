package org.speckitty.commandcli.domain.authority;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Small in-memory frontmatter adapter mirroring WP08's production reader. */
final class CorpusMissionFacts implements MissionFacts {
  private final Map<String, Map<String, Object>> frontmatter;

  CorpusMissionFacts(Map<String, String> tasks) {
    frontmatter = new java.util.LinkedHashMap<>();
    tasks.forEach((filename, text) -> parse(filename, text));
  }

  @Override
  public DependencyDeclaration dependencies(String wpId) {
    Object value = frontmatter.getOrDefault(wpId, Map.of()).get("dependencies");
    if (value == null) return new DependencyDeclaration.None();
    if (value instanceof List<?> list) return strings(list);
    if (value instanceof String text) {
      String stripped = text.strip();
      if (stripped.equals("[]") || stripped.isEmpty()) return new DependencyDeclaration.None();
      return strings(List.of(stripped.split("\\s*,\\s*")));
    }
    return new DependencyDeclaration.Unresolvable("dependencies is neither a string nor a list");
  }

  private DependencyDeclaration strings(List<?> values) {
    List<String> ids = new ArrayList<>();
    for (Object value : values) {
      if (!(value instanceof String id) || !id.matches("WP\\d+")) {
        return new DependencyDeclaration.Unresolvable("invalid dependency declaration");
      }
      ids.add(id);
    }
    return ids.isEmpty() ? new DependencyDeclaration.None() : new DependencyDeclaration.Declared(ids);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<String> subtaskRoster(String wpId) {
    Object value = frontmatter.getOrDefault(wpId, Map.of()).get("subtasks");
    return value instanceof List<?> list ? (List<String>) list : List.of();
  }

  @SuppressWarnings("unchecked")
  private void parse(String filename, String text) {
    if (!text.startsWith("---\n")) return;
    int end = text.indexOf("\n---", 4);
    if (end < 0) return;
    Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text.substring(4, end));
    if (!(loaded instanceof Map<?, ?> map)) return;
    String wpId = map.get("work_package_id") instanceof String id ? id : filename.replaceFirst("([-.].*)?\\.md$", "");
    frontmatter.put(wpId, (Map<String, Object>) map);
  }
}
