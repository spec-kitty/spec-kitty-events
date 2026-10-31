package org.speckitty.commandcli.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/** Runtime hints for the native image (ADR 0003: hints in code, never hand-written reflect-config): the build-info resource. */
public final class CommandCliHints implements RuntimeHintsRegistrar {

  static final String BUILD_INFO = "META-INF/build-info.properties";

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.resources().registerPattern(BUILD_INFO);
  }
}
