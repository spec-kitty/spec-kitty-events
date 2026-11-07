package org.speckitty.commandcli.domain.authority;

import java.util.Map;

/** Core's actor projection, deliberately not a parser for compact actor strings. */
public final class Actors {
  private Actors() {}

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
