package org.speckitty.commandcli.domain.authority;

import java.util.Map;

/**
 * A transition row's {@code event_id} the way core's reducer holds it: the raw JSON scalar, or {@code None}.
 *
 * <p>The two cases are modelled apart on purpose (operator ruling M2-R1). {@link Absent} is core's {@code None}: one ordinary
 * dict key equal only to itself, so id-less rows dedupe into one group, but the setter lookup ({@code _find_event_by_id}) finds nothing
 * for it, so an id-less row is never a work package's current-lane setter. {@link Identified} is any other id, compared by Python
 * equality ({@link EventIds}); a string id spelled {@code "None"} is {@link Identified} and never equal to {@link Absent}.
 * Only {@link #of(Map)} builds either variant.
 */
public sealed interface TransitionId permits TransitionId.Identified, TransitionId.Absent {
  /** The id of a transition row: {@link Absent} when {@code event_id} is missing or JSON {@code null}, else {@link Identified}. */
  static TransitionId of(Map<String, Object> row) {
    Object raw = row.get("event_id");
    return raw == null ? Absent.INSTANCE : new Identified(raw);
  }

  /** The Python-equality key, for maps and for ordering ({@link EventIds#key}); {@link Absent} keys as {@code None}. */
  Object key();

  /** Whether the id can name a setter; {@link Absent} cannot. */
  boolean identified();

  /** The id as the fold exposes it ({@code last_event_id}): its spelling, or {@code null} for {@link Absent}. */
  String spelling();

  /** Core's tuple-tail comparison: {@code Absent} equals {@code Absent}; against an identified id it is unorderable. */
  default int compareTo(TransitionId other) {
    return EventIds.compare(key(), other.key());
  }

  /** An id carried by the row. Equality and hashing are Python equality of the raw scalar. */
  final class Identified implements TransitionId {
    private final Object raw;
    private final Object key;

    private Identified(Object raw) {
      this.raw = raw;
      this.key = EventIds.key(raw);
    }

    @Override public Object key() { return key; }
    @Override public boolean identified() { return true; }
    @Override public String spelling() { return WriteFold.pyText(raw); }
    @Override public boolean equals(Object other) { return other instanceof Identified id && key.equals(id.key); }
    @Override public int hashCode() { return key.hashCode(); }
    @Override public String toString() { return "Identified(" + spelling() + ")"; }
  }

  /** No {@code event_id}: core's {@code None}. One value, equal only to itself, and never a setter. */
  final class Absent implements TransitionId {
    private static final Absent INSTANCE = new Absent();

    private Absent() {}

    @Override public Object key() { return EventIds.key(null); }
    @Override public boolean identified() { return false; }
    @Override public String spelling() { return null; }
    @Override public String toString() { return "Absent"; }
  }
}
