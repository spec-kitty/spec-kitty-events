package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthoritySupportTests {
  @Test
  void valueObjectsAreImmutableAndOperationsParseWireNames() {
    var declared = new DependencyDeclaration.Declared(new java.util.ArrayList<>(List.of("WP01")));
    assertThat(declared.wpIds()).containsExactly("WP01");
    assertThat(new DependencyDeclaration.None()).isInstanceOf(DependencyDeclaration.class);
    assertThat(new DependencyDeclaration.Unresolvable("bad").message()).isEqualTo("bad");
    assertThat(Operation.fromWire(null)).isEqualTo(Operation.TRANSITION);
    assertThat(Operation.fromWire("start_review")).isEqualTo(Operation.START_REVIEW);
    assertThat(Operation.fromWire("start_implementation")).isEqualTo(Operation.START_IMPLEMENTATION);
    var logValue = new LogLine.Value(Map.of("kind", "annotation"));
    assertThat(logValue.row()).containsKey("kind");
    assertThat(new LogLine.Blank()).isInstanceOf(LogLine.class);
    assertThat(new LogLine.Unparseable()).isInstanceOf(LogLine.class);
    assertThat(new UnreducibleLog("bad")).hasMessage("bad");
  }

  @Test
  void foldViewDefaultsToUninitializedAndIsReadOnly() {
    FoldView view = new FoldView(new java.util.HashMap<>());
    assertThat(view.known("WP01")).isFalse();
    assertThat(view.of("WP01").lane()).isEqualTo(org.speckitty.missionstatus.domain.model.Lane.UNINITIALIZED);
    assertThat(view.all()).isUnmodifiable();
    var state = new WpWriteState(org.speckitty.missionstatus.domain.model.Lane.PLANNED,
        "actor", "actor", "role", "agent", "operator", true, Map.of(), null, null, Map.of());
    FoldView known = new FoldView(Map.of("WP01", state));
    assertThat(known.known("WP01")).isTrue();
    assertThat(known.of("WP01")).isSameAs(state);
  }
}
