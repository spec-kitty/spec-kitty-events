package org.speckitty.commandcli.domain.authority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Core's actor projection, deliberately not a parser for compact actor strings. */
public final class Actors {
  private static final Set<String> ACTOR_FIELDS = Set.of("role", "profile", "tool", "model");
  private static final Set<String> STATUS_ACTOR_FIELDS = Set.of("role", "profile", "tool", "model", "profile_source");
  private static final Set<String> PROFILE_SOURCES = Set.of("invocation", "flag", "agent_string", "wp_frontmatter");

  private Actors() {}

  /**
   * Core's {@code decode_actor}: a structured actor has exactly the key set {role, profile, tool, model} or that plus
   * profile_source, every value a string or null, and profile_source a known source, null exactly when profile is null. Any other
   * value is a plain actor. Throws {@link IllegalArgumentException} for a structured actor the fold refuses.
   */
  public static Object decode(Object actor) {
    if (!(actor instanceof Map<?, ?> structured)) {
      if (actor == null) return "None";
      if (actor instanceof Boolean flag) return flag ? "True" : "False";
      return actor;
    }
    if (!structured.keySet().equals(ACTOR_FIELDS) && !structured.keySet().equals(STATUS_ACTOR_FIELDS)) {
      throw new IllegalArgumentException("structured actor has an unexpected key set");
    }
    for (String field : STATUS_ACTOR_FIELDS) {
      Object value = structured.get(field);
      if (value != null && !(value instanceof String)) {
        throw new IllegalArgumentException("structured actor field " + field + " must be a string or null");
      }
    }
    if (structured.containsKey("profile_source")) validateProfileSource(structured);
    @SuppressWarnings("unchecked")
    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) structured);
    return copy;
  }

  /** True when the write fold can decode this actor, so a row carrying it can be read back from the log. */
  public static boolean decodable(Object actor) {
    try {
      decode(actor);
      return true;
    } catch (IllegalArgumentException refused) {
      return false;
    }
  }

  private static void validateProfileSource(Map<?, ?> actor) {
    Object source = actor.get("profile_source");
    if (source != null && !PROFILE_SOURCES.contains(source)) {
      throw new IllegalArgumentException("unknown structured actor profile_source");
    }
    if ((source == null) != (actor.get("profile") == null)) {
      throw new IllegalArgumentException("profile_source must be null exactly when profile is null");
    }
  }

  public static String project(Object actor) {
    if (actor instanceof Map<?, ?> structured) {
      Object tool = structured.get("tool");
      Object role = structured.get("role");
      Object identity = truthy(tool) ? tool : truthy(role) ? role : "";
      return String.valueOf(identity);
    }
    return actor == null ? "" : String.valueOf(actor);
  }

  public static boolean isMigration(Object actor) {
    return actor != null && project(actor).startsWith("migration:");
  }

  private static boolean truthy(Object value) {
    return value != null && !String.valueOf(value).isEmpty();
  }
}
