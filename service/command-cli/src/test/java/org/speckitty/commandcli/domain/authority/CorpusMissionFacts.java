package org.speckitty.commandcli.domain.authority;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Small in-memory frontmatter adapter mirroring WP08's production reader. */
final class CorpusMissionFacts implements MissionFacts {
  private final Map<String, Map<String, Object>> frontmatter;
  private final Map<String, String> tasks;
  private final Set<String> unreadable = new HashSet<>();

  CorpusMissionFacts(Map<String, String> tasks) {
    this.tasks = Map.copyOf(tasks);
    frontmatter = new java.util.LinkedHashMap<>();
    tasks.forEach((filename, text) -> parse(filename, text));
  }

  @Override
  public DependencyDeclaration dependencies(String wpId) {
    List<String> matches = matchingFiles(wpId);
    if (matches.isEmpty()) return new DependencyDeclaration.None();
    if (matches.size() != 1 || unreadable.contains(matches.getFirst()) || !frontmatter.containsKey(wpId)) {
      return new DependencyDeclaration.Unresolvable("cannot resolve declared dependencies");
    }
    Object value = frontmatter.get(wpId).get("dependencies");
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
      if (!(value instanceof String id)) return new DependencyDeclaration.Unresolvable("invalid dependency declaration");
      if (!id.strip().isEmpty()) ids.add(id.strip());
    }
    return ids.isEmpty() ? new DependencyDeclaration.None() : new DependencyDeclaration.Declared(ids);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<String> subtaskRoster(String wpId) {
    Object value = frontmatter.getOrDefault(wpId, Map.of()).get("subtasks");
    return value instanceof List<?> list ? (List<String>) list : List.of();
  }

  private List<String> matchingFiles(String wpId) {
    Pattern pattern = Pattern.compile("^" + Pattern.quote(wpId) + "(?:[-_.]|\\.md$)");
    return tasks.keySet().stream()
        .filter(filename -> !filename.equalsIgnoreCase("readme.md") && pattern.matcher(filename).find())
        .sorted()
        .toList();
  }

  @SuppressWarnings("unchecked")
  private void parse(String filename, String text) {
    try {
      if (!text.startsWith("---\n")) {
        unreadable.add(filename);
        return;
      }
      int end = text.indexOf("\n---", 4);
      if (end < 0) {
        unreadable.add(filename);
        return;
      }
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text.substring(4, end));
      if (!(loaded instanceof Map<?, ?> map)) {
        unreadable.add(filename);
        return;
      }
      String wpId = map.get("work_package_id") instanceof String id ? id : filename.replaceFirst("([-.].*)?\\.md$", "");
      frontmatter.put(wpId, (Map<String, Object>) map);
    } catch (RuntimeException exception) {
      unreadable.add(filename);
    }
  }
}
