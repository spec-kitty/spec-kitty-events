package org.speckitty.commandcli.adapter.out.mission;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.SubtaskRoster;
import org.speckitty.missionstatus.domain.model.PyText;

/** Core's frontmatter value rules, shared by the disk and corpus MissionFacts adapters. */
public final class FactValues {
  private static final Pattern WP_ID = Pattern.compile("^WP\\d{2,}$");
  private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{7,40}$");
  private static final Pattern PYTHON_INT = Pattern.compile("^[+-]?\\d+(?:_\\d+)*$", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Set<String> OPTIONAL_STRINGS = Set.of(
      "base_branch", "created_at", "planning_base_branch", "merge_target_branch", "branch_strategy",
      "priority", "execution_mode", "authoritative_surface", "scope", "task_type", "phases", "assignee",
      "model", "agent_profile", "role", "shell_pid_created_at", "feature_slug", "activity_log",
      "review_status", "reviewed_by", "approved_by", "reviewer_agent", "reviewer_shell_pid", "review_feedback", "review_feedback_file",
      "subtitle", "description", "estimated_duration", "mission_id", "mission_number", "mission_slug", "status", "wp_code",
      "work_package_title");
  private static final Set<String> STRING_LISTS = Set.of("owned_files", "create_intent", "tags");
  private static final Set<String> ALLOWED = Set.of(
      "work_package_id", "title", "dependencies", "base_branch", "base_commit", "created_at",
      "planning_base_branch", "merge_target_branch", "branch_strategy", "requirement_refs", "tracker_refs",
      "priority", "execution_mode", "owned_files", "create_intent", "authoritative_surface", "scope", "task_type",
      "subtasks", "phase", "phases", "assignee", "agent", "model", "agent_profile", "role", "shell_pid",
      "shell_pid_created_at", "history", "lane", "feature_slug", "activity_log", "review_status", "reviewed_by",
      "approved_by", "reviewer", "reviewer_agent", "reviewer_shell_pid", "review_feedback", "review_feedback_file",
      "subtitle", "description", "estimated_duration", "tags", "mission_id", "mission_number", "mission_slug",
      "status", "wp_code", "work_package_title");
  private static final Set<String> LANES = Set.of(
      "planned", "claimed", "in_progress", "for_review", "in_review", "approved", "done", "blocked", "canceled", "doing");

  private FactValues() {}

  public static DependencyDeclaration dependencies(Map<String, Object> frontmatter) {
    Object value = frontmatter.get("dependencies");
    if (value instanceof List<?> list) return dependencyStrings(list);
    if (value instanceof String text) {
      String stripped = PyText.strip(text);
      if (stripped.isEmpty() || stripped.equals("[]")) return new DependencyDeclaration.None();
      return dependencyStrings(List.of(stripped.split("\\s*,\\s*")));
    }
    return value == null ? new DependencyDeclaration.None()
        : new DependencyDeclaration.Unresolvable("dependencies is neither a string nor a list");
  }

  public static SubtaskRoster roster(Map<String, Object> frontmatter) {
    if (!frontmatter.containsKey("subtasks") || !validMetadata(frontmatter)
        || !(frontmatter.get("subtasks") instanceof List<?> list)) {
      return new SubtaskRoster.Unresolvable();
    }
    Set<String> seen = new LinkedHashSet<>();
    for (Object item : list) {
      String id = PyText.strip(pythonString(item));
      if (!id.isEmpty()) seen.add(id);
    }
    return new SubtaskRoster.Resolved(List.copyOf(seen));
  }

  private static DependencyDeclaration dependencyStrings(List<?> values) {
    List<String> ids = new ArrayList<>();
    for (Object value : values) {
      if (!(value instanceof String id)) return new DependencyDeclaration.Unresolvable("invalid dependency declaration");
      if (!PyText.strip(id).isEmpty()) ids.add(PyText.strip(id));
    }
    return ids.isEmpty() ? new DependencyDeclaration.None() : new DependencyDeclaration.Declared(ids);
  }

  private static boolean validMetadata(Map<String, Object> frontmatter) {
    for (Object key : frontmatter.keySet()) {
      if (!(key instanceof String name) || !ALLOWED.contains(name)) return false;
    }
    if (!(frontmatter.get("work_package_id") instanceof String id) || !WP_ID.matcher(id).matches()) return false;
    return validStrings(frontmatter) && validLists(frontmatter) && validSpecialFields(frontmatter);
  }

  private static boolean validStrings(Map<String, Object> frontmatter) {
    for (String key : OPTIONAL_STRINGS) {
      Object value = frontmatter.get(key);
      if (value != null && !(value instanceof String)) return false;
    }
    Object title = frontmatter.containsKey("title") ? frontmatter.get("title") : frontmatter.get("work_package_title");
    return title == null || title instanceof String text && !PyText.isBlank(text);
  }

  private static boolean validLists(Map<String, Object> frontmatter) {
    for (String key : STRING_LISTS) {
      if (frontmatter.containsKey(key) && !stringList(frontmatter.get(key))) return false;
    }
    for (String key : List.of("requirement_refs", "tracker_refs")) {
      if (!frontmatter.containsKey(key)) continue;
      Object value = frontmatter.get(key);
      if (!(value instanceof String) && !stringList(value)) return false;
    }
    return (!frontmatter.containsKey("dependencies") || validDependencyMetadata(frontmatter.get("dependencies")))
        && (!frontmatter.containsKey("subtasks") || frontmatter.get("subtasks") instanceof List<?>)
        && (!frontmatter.containsKey("history") || frontmatter.get("history") instanceof List<?>);
  }

  private static boolean validSpecialFields(Map<String, Object> frontmatter) {
    Object commit = frontmatter.get("base_commit");
    if (commit != null && (!(commit instanceof String text) || !text.equals("unknown") && !COMMIT.matcher(text).matches())) {
      return false;
    }
    Object lane = frontmatter.get("lane");
    if (lane != null && !lane.equals("") && (!(lane instanceof String text) || !LANES.contains(text))) return false;
    return !frontmatter.containsKey("shell_pid") || validPid(frontmatter.get("shell_pid"));
  }

  private static boolean validDependencyMetadata(Object value) {
    return value instanceof String || stringList(value);
  }

  private static boolean stringList(Object value) {
    return value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
  }

  private static boolean validPid(Object value) {
    return switch (value) {
      case null -> true;
      case Boolean ignored -> true;
      case BigInteger ignored -> true;
      case Number number -> Double.isFinite(number.doubleValue());
      case String text -> text.isEmpty() || validPythonInteger(text);
      default -> false;
    };
  }

  private static boolean validPythonInteger(String value) {
    String text = PyText.strip(value);
    if (!PYTHON_INT.matcher(text).matches()) {
      return false;
    }
    try {
      new BigInteger(text.replace("_", ""));
      return true;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static String pythonString(Object value) {
    return switch (value) {
      case null -> "None";
      case Boolean flag -> flag ? "True" : "False";
      default -> String.valueOf(value);
    };
  }
}
