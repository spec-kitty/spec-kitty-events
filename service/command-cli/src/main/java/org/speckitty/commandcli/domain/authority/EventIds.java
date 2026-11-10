package org.speckitty.commandcli.domain.authority;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.speckitty.missionstatus.domain.model.CodePoints;

/**
 * A transition's {@code event_id} the way core's reducer holds it. {@code StatusEvent.from_dict} keeps the raw JSON scalar, so the
 * reducer deduplicates ids by Python equality ({@code 1 == 1.0 == True}, {@code "1" != 1}) and orders them in the tail of every
 * {@code (at, ..., event_id)} sort key, where a string against a non-string, or {@code None} against anything else, raises
 * {@code TypeError} and makes the log unreducible. Two ids that are equal compare equal and are never compared with {@code <}, so equal
 * keys never raise.
 */
public final class EventIds {
  /** Python's {@code None}: equal only to itself, ordered against nothing. */
  private enum Null { INSTANCE }

  private EventIds() {}

  /** The Python-equality key of a decoded JSON {@code event_id}: text stays text, booleans and numbers become one exact decimal. */
  static Object key(Object id) {
    return switch (id) {
      case null -> Null.INSTANCE;
      case Boolean flag -> flag ? BigDecimal.ONE : BigDecimal.ZERO;
      case BigInteger integer -> new BigDecimal(integer).stripTrailingZeros();
      case Double number -> new BigDecimal(number).stripTrailingZeros();
      default -> id;
    };
  }

  /**
   * Whether two decoded {@code event_id}s are the same id to core's reducer (Python equality: {@code 1 == 1.0 == True},
   * {@code "1" != 1}). Two absent ids ({@code None}) are not compared here: callers that need "is this a repeat" treat a missing id
   * as no id, as the fold's own replay check does.
   */
  public static boolean sameId(Object left, Object right) {
    return left != null && right != null && key(left).equals(key(right));
  }

  /** {@code a < b} on two keys as Python's tuple comparison reaches it, or an {@link UnreducibleLog} where Python raises. */
  static int compare(Object left, Object right) {
    if (left.equals(right)) return 0;
    if (left instanceof String a && right instanceof String b) return CodePoints.compare(a, b);
    if (left instanceof BigDecimal a && right instanceof BigDecimal b) return a.compareTo(b);
    throw new UnreducibleLog("Event ids of different types cannot be ordered");
  }
}
