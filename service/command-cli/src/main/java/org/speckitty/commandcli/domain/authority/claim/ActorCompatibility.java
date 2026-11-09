package org.speckitty.commandcli.domain.authority.claim;

import java.util.Set;
import org.speckitty.commandcli.domain.authority.Actors;

/** Tool-scoped lifecycle actor comparison; role deliberately lives on another channel. */
public final class ActorCompatibility {
  private static final Set<String> GENERIC_EXISTING = Set.of("implement-command", "unknown", "user");

  private ActorCompatibility() {}

  public static String key(Object actor) {
    if (actor == null) return null;
    String projected = Actors.project(actor);
    if (projected.isEmpty()) return null;
    String tool = actor instanceof String && !projected.startsWith(":")
        ? projected.split(":", 2)[0]
        : projected;
    String value = tool.strip();
    return value.isEmpty() ? null : value;
  }

  public static boolean compatible(Object existing, Object requested, boolean allowGenericExisting) {
    String existingKey = key(existing);
    String requestedKey = key(requested);
    if (existingKey == null || requestedKey == null || existingKey.equals(requestedKey)) return true;
    return allowGenericExisting && isGenericKey(existingKey);
  }

  public static boolean sameRealActor(Object left, Object right) {
    String leftKey = key(left);
    String rightKey = key(right);
    return leftKey != null && rightKey != null && !isGenericKey(leftKey) && leftKey.equals(rightKey);
  }

  private static boolean isGenericKey(String key) {
    return GENERIC_EXISTING.contains(key);
  }
}
