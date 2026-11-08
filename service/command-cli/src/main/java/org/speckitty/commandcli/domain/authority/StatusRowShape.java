package org.speckitty.commandcli.domain.authority;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.speckitty.missionstatus.domain.model.Lane;
import org.speckitty.missionstatus.domain.model.PyText;

/**
 * The one rule for which status-log rows core's reader accepts, shared by the HTTP edge (a new row) and by {@link WriteFold} (every
 * row of the log, and so the append verifier's read-back). It ports core's {@code read_events} path at {@code 512567ee}:
 * {@code StatusEvent.from_dict} and {@code InnerStateChanged.from_dict} with the dataclasses they build, and the reducer's own
 * assumptions about those fields. Each check names the Python call that fails, and a row is refused exactly when core's reader
 * raises on it. Core's dataclasses never check a field's type; a wrongly typed field fails later, in a coercion, a lookup or the
 * reducer's sort, and that is the failure ported here, so a shape core reads (an integer {@code force}, a string {@code reason})
 * is accepted.
 *
 * <p>A refusal is the fixed name of the field, never a value (FR-025). Rows are the decoded JSON values: {@code Map}, {@code List},
 * {@code String}, {@code BigInteger}, {@code Double}, {@code Boolean} and null.
 *
 * <p>One implementation answers at two points of the write order (FR-026). {@link Scope#READ_BACK} is the whole rule: the fold applies it
 * to every row, so it is what the append verifier proves before anything is acknowledged. {@link Scope#EDGE} is the part that can be
 * decided before the authority and the Mission lookup, which are never pre-empted: the contract requires only {@code event_id} of a row
 * (a row that omits a field the reader needs is refused by the read-back, after the authority), and the authority owns the lane
 * vocabulary and the done-evidence guard (core refuses those with their own codes, before it builds an event). Every shape the edge
 * lets through is therefore either refused by the authority or caught by the read-back; none is acknowledged.
 */
public final class StatusRowShape {
  private static final List<String> TRANSITION_REQUIRED =
      List.of("event_id", "wp_id", "from_lane", "to_lane", "at", "actor", "force", "execution_mode");
  private static final List<String> ANNOTATION_REQUIRED = List.of("event_id", "wp_id", "at", "actor", "delta");
  private static final List<String> REVIEW_APPROVAL_KEYS = List.of("reviewer", "verdict", "reference");
  private static final List<String> REPO_EVIDENCE_KEYS = List.of("repo", "branch", "commit");
  private static final List<String> VERIFICATION_KEYS = List.of("command", "result", "summary");
  private static final List<String> REVIEW_OVERRIDE_KEYS = List.of("at", "actor", "wp_id", "reason");
  /** {@code ULID_PATTERN.match}: Python's {@code $} also matches before one final newline, which is why this is not {@code matches}. */
  private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}\\n?\\z");
  /** CPython's limit on the digits of an integer built from text (3.11.15). */
  private static final int MAX_INTEGER_TEXT_DIGITS = 4300;
  private static final Set<String> SUBTASK_ALIASES = Set.of("skipped", "not_applicable");

  /** Where in the write order the rule is applied. */
  public enum Scope {
    /** Before the authority: the shapes of the fields a row carries, leaving missing fields, the done-evidence guard and lanes to later steps. */
    EDGE,
    /** After the authority and before the append: every shape core's reader refuses. */
    READ_BACK
  }

  private StatusRowShape() {}

  /** The first field of a transition row that core's reader cannot read at this {@code scope}, or empty. */
  public static Optional<String> transition(Map<String, Object> row, Scope scope) {
    if (scope == Scope.READ_BACK) {
      for (String field : TRANSITION_REQUIRED) {
        if (!row.containsKey(field)) return Optional.of(field);
      }
    }
    Optional<String> identity = identity(row);
    return identity.isPresent() ? identity : coercions(row, scope);
  }

  /** The first field of an annotation row that core's reader cannot read at this {@code scope}, or empty. */
  public static Optional<String> annotation(Map<String, Object> row, Scope scope) {
    if (scope == Scope.READ_BACK) {
      for (String field : ANNOTATION_REQUIRED) {
        if (!row.containsKey(field)) return Optional.of(field);
      }
    }
    if (row.containsKey("event_id") && (!(row.get("event_id") instanceof String id) || !ULID.matcher(id).find())) {
      return Optional.of("event_id");
    }
    if (row.containsKey("actor") && !Actors.decodable(row.get("actor"))) return Optional.of("actor");
    if (!row.containsKey("delta")) return Optional.empty();
    return row.get("delta") instanceof Map<?, ?> delta ? delta(castMap(delta)) : Optional.of("delta");
  }

  // ---- transition rows --------------------------------------------------------------------------------------------------------

  /**
   * The fields the reducer hashes and orders: the ids go into a {@code seen} set and the {@code (at, event_id)} sort key, so
   * {@code at} must be text (an integer or null cannot be ordered against the text of the other rows). The lane vocabulary is the lane
   * authority's ({@link Lane#fromWire} in {@link WriteFold}), and a non-text lane is refused by the edge before this rule.
   */
  private static Optional<String> identity(Map<String, Object> row) {
    for (String field : List.of("event_id", "wp_id")) {
      if (row.get(field) instanceof List<?> || row.get(field) instanceof Map<?, ?>) return Optional.of(field);
    }
    if (row.containsKey("at") && !(row.get("at") instanceof String)) return Optional.of("at");
    return !row.containsKey("actor") || Actors.decodable(row.get("actor")) ? Optional.empty() : Optional.of("actor");
  }

  private static Optional<String> coercions(Map<String, Object> row, Scope scope) {
    if (slugRefused(row)) return Optional.of("mission_slug");
    if (scope == Scope.READ_BACK && truthy(row.get("evidence")) && !doneEvidence(row.get("evidence"))) return Optional.of("evidence");
    if (truthy(row.get("review_result")) && !hasKeys(row.get("review_result"), REVIEW_APPROVAL_KEYS)) {
      return Optional.of("review_result");
    }
    return claim(row) && truthy(row.get("policy_metadata")) && !(row.get("policy_metadata") instanceof Map<?, ?>)
        ? Optional.of("policy_metadata") : Optional.empty();
  }

  /** {@code planned -> claimed}: the one row whose {@code policy_metadata} the reader indexes ({@code (event.policy_metadata or {}).get}). */
  private static boolean claim(Map<String, Object> row) {
    return "planned".equals(row.get("from_lane")) && "claimed".equals(row.get("to_lane"));
  }

  /** {@code mission_slug = data.get("mission_slug") or data.get("feature_slug", "")}, which the slug resolver then calls {@code strip} on. */
  private static boolean slugRefused(Map<String, Object> row) {
    Object slug = truthy(row.get("mission_slug")) ? row.get("mission_slug") : row.getOrDefault("feature_slug", "");
    return truthy(slug) && !(slug instanceof String);
  }

  /** {@code DoneEvidence.from_dict}: a review with its three keys, then {@code repos} and {@code verification} as lists of keyed rows. */
  private static boolean doneEvidence(Object evidence) {
    if (!(evidence instanceof Map<?, ?> map) || !hasKeys(map.get("review"), REVIEW_APPROVAL_KEYS)) return false;
    return keyedRows(map, "repos", REPO_EVIDENCE_KEYS) && keyedRows(map, "verification", VERIFICATION_KEYS);
  }

  /**
   * {@code [X.from_dict(item) for item in data.get(key, [])]}: an absent key is empty, an array holds objects with the keys, and a
   * non-empty string or object iterates into strings that cannot be indexed. Null and scalars are not iterable.
   */
  private static boolean keyedRows(Map<?, ?> evidence, String key, List<String> keys) {
    if (!evidence.containsKey(key)) return true;
    Object rows = evidence.get(key);
    if (rows instanceof List<?> list) return list.stream().allMatch(item -> hasKeys(item, keys));
    return rows instanceof String text ? text.isEmpty() : rows instanceof Map<?, ?> map && map.isEmpty();
  }

  // ---- annotation deltas ------------------------------------------------------------------------------------------------------

  /** {@code WPInnerStateDelta.from_dict}: the five coercions it applies to a delta. */
  private static Optional<String> delta(Map<String, Object> delta) {
    if (!subtasks(delta.get("subtasks"))) return Optional.of("delta.subtasks");
    if (delta.get("review") != null && !hasKeys(delta.get("review"), REVIEW_OVERRIDE_KEYS)) return Optional.of("delta.review");
    if (delta.get("shell_pid") != null && !integerLike(delta.get("shell_pid"))) return Optional.of("delta.shell_pid");
    if (!iterable(delta.get("tracker_refs"))) return Optional.of("delta.tracker_refs");
    if (!iterable(delta.get("tracker_refs_replace")) || !hashableItems(delta.get("tracker_refs_replace"))) {
      return Optional.of("delta.tracker_refs_replace");
    }
    return Optional.empty();
  }

  /** {@code {str(id): ... Lane(value) ...}}: an object whose values are lanes or the {@code skipped} and {@code not_applicable} spellings. */
  private static boolean subtasks(Object subtasks) {
    if (subtasks == null) return true;
    if (!(subtasks instanceof Map<?, ?> map)) return false;
    return map.values().stream().allMatch(value -> value instanceof String text && (SUBTASK_ALIASES.contains(text) || lane(text)));
  }

  /** {@code list(value)}: null is absent, a string, array or object iterates, a number or boolean does not. */
  private static boolean iterable(Object value) {
    return value == null || value instanceof String || value instanceof List<?> || value instanceof Map<?, ?>;
  }

  /** The reducer builds a set of the replacement refs, so an element must be hashable: arrays and objects are not. */
  private static boolean hashableItems(Object value) {
    return !(value instanceof List<?> list) || list.stream().noneMatch(item -> item instanceof List<?> || item instanceof Map<?, ?>);
  }

  // ---- shared helpers ---------------------------------------------------------------------------------------------------------

  private static boolean lane(String value) {
    try {
      Lane.fromWire(value);
      return true;
    } catch (IllegalArgumentException unknown) {
      return false;
    }
  }

  /** {@code value["a"], value["b"], ...}: an object that holds every key. */
  private static boolean hasKeys(Object value, List<String> keys) {
    return value instanceof Map<?, ?> map && keys.stream().allMatch(map::containsKey);
  }

  /** Python truthiness of a decoded JSON value. */
  static boolean truthy(Object value) {
    return switch (value) {
      case null -> false;
      case Boolean flag -> flag;
      case String text -> !text.isEmpty();
      case BigInteger integer -> integer.signum() != 0;
      case Number number -> number.doubleValue() != 0.0;
      case List<?> list -> !list.isEmpty();
      case Map<?, ?> map -> !map.isEmpty();
      default -> true;
    };
  }

  /** {@code int(value)}: an integer, a boolean, a finite float, or text Python reads as an integer. */
  private static boolean integerLike(Object value) {
    return switch (value) {
      case Boolean ignored -> true;
      case BigInteger ignored -> true;
      case Double number -> Double.isFinite(number);
      case String text -> integerText(text);
      default -> false;
    };
  }

  /**
   * {@code int(text)} in base 10: surrounding whitespace, one sign, then decimal digits (any script) with single underscores between
   * digits, at most {@value #MAX_INTEGER_TEXT_DIGITS} of them.
   */
  private static boolean integerText(String text) {
    String body = PyText.strip(text);
    if (body.startsWith("+") || body.startsWith("-")) body = body.substring(1);
    int digits = 0;
    boolean previousDigit = false;
    for (int index = 0; index < body.length(); ) {
      int point = body.codePointAt(index);
      index += Character.charCount(point);
      boolean digit = Character.getType(point) == Character.DECIMAL_DIGIT_NUMBER;
      boolean underscore = point == '_' && previousDigit && index < body.length();
      if (!digit && !underscore) return false;
      if (digit) digits++;
      previousDigit = digit;
    }
    return digits > 0 && digits <= MAX_INTEGER_TEXT_DIGITS;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }
}
