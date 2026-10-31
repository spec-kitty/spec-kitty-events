package org.speckitty.commandcli.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RefusalBody;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * The native image keeps only what is hinted. The refusal body is serialised by hand in the filters, outside any controller return type,
 * so Spring's AOT never sees it: without a binding hint, Jackson cannot read its record components and every refusal fails in the native
 * binary (the first native CI run of #172 failed 46 native-tag tests this way).
 */
class CommandCliHintsTest {

  private final RuntimeHints hints = registered();

  private static RuntimeHints registered() {
    RuntimeHints hints = new RuntimeHints();
    new CommandCliHints().registerHints(hints, CommandCliHintsTest.class.getClassLoader());
    return hints;
  }

  @Test
  void theBuildInfoResourceIsKept() {
    assertThat(RuntimeHintsPredicates.resource().forResource(CommandCliHints.BUILD_INFO)).accepts(hints);
  }

  @Test
  void theRefusalBodyIsBoundForJacksonWithEveryRecordAccessor() throws NoSuchMethodException {
    assertThat(RuntimeHintsPredicates.reflection().onType(RefusalBody.class).withMemberCategories(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
        .accepts(hints);
    for (RecordComponent component : RefusalBody.class.getRecordComponents()) {
      assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(component.getAccessor())).as(component.getName()).accepts(hints);
    }
  }
}
