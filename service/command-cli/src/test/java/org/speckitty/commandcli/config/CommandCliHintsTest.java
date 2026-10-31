package org.speckitty.commandcli.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/** The native image keeps only what is hinted: the build-info resource the handshake's {@code serviceVersion} is read from. */
class CommandCliHintsTest {

  @Test
  void theBuildInfoResourceIsKept() {
    RuntimeHints hints = new RuntimeHints();
    new CommandCliHints().registerHints(hints, CommandCliHintsTest.class.getClassLoader());
    assertThat(RuntimeHintsPredicates.resource().forResource(CommandCliHints.BUILD_INFO)).accepts(hints);
  }
}
