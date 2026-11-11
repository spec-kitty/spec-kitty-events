package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The id model's invariants (data-model "TransitionId"; operator ruling M2-R1), pinned directly. */
class TransitionIdTest {

  private static TransitionId id(Object raw) {
    Map<String, Object> row = new HashMap<>();
    row.put("event_id", raw);
    return TransitionId.of(row);
  }

  @Test
  void aMissingOrNullIdIsTheOneAbsentValue() {
    TransitionId missing = TransitionId.of(Map.of("wp_id", "WP01"));
    TransitionId nullId = id(null);
    assertThat(missing).isSameAs(nullId);
    assertThat(missing.identified()).isFalse();
    assertThat(missing.spelling()).isNull();
    assertThat(missing).hasToString("Absent");
    assertThat(missing.compareTo(nullId)).isZero();
    assertThat(Map.of(missing, "first").get(nullId)).isEqualTo("first");
  }

  @Test
  void identifiedIdsArePythonEqual() {
    TransitionId one = id(BigInteger.ONE);
    assertThat(one.identified()).isTrue();
    assertThat(one).isEqualTo(id(1.0)).isEqualTo(id(true)).hasSameHashCodeAs(id(1.0));
    assertThat(one).isNotEqualTo(id("1")).isNotEqualTo(TransitionId.of(Map.of())).isNotEqualTo("1");
    assertThat(id(true).spelling()).isEqualTo("True");
    assertThat(id("01J6XW9KQT7M0YB3N4R5CQZ2EZ")).hasToString("Identified(01J6XW9KQT7M0YB3N4R5CQZ2EZ)");
  }

  @Test
  void theStringNoneIsIdentifiedAndNeverAbsent() {
    TransitionId none = id("None");
    TransitionId absent = id(null);
    assertThat(none.identified()).isTrue();
    assertThat(none).isNotEqualTo(absent);
    assertThat(absent).isNotEqualTo(none);
    assertThat(none.key()).isNotEqualTo(absent.key());
  }

  @Test
  void orderingFollowsCoreAndAbsentAgainstAnIdIsUnorderable() {
    assertThat(id("A").compareTo(id("B"))).isNegative();
    assertThat(id(BigInteger.TWO).compareTo(id(1.5))).isPositive();
    assertThatThrownBy(() -> id(null).compareTo(id("A"))).isInstanceOf(UnreducibleLog.class);
    assertThatThrownBy(() -> id("A").compareTo(id(null))).isInstanceOf(UnreducibleLog.class);
  }
}
