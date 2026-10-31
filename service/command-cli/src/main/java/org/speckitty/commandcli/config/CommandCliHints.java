package org.speckitty.commandcli.config;

import org.speckitty.commandcli.adapter.in.http.edge.RefusalBody;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Runtime hints for the native image (ADR 0003: hints in code, never hand-written reflect-config):
 *
 * <ul>
 *   <li>the build-info resource, which the handshake's {@code serviceVersion} is read from;
 *   <li>Jackson binding for {@link RefusalBody}. The filters serialise it by hand, outside any controller return type, so Spring's AOT
 *       does not register it; without the hint the record's components are unreadable in the native image and every refusal fails.
 * </ul>
 */
public final class CommandCliHints implements RuntimeHintsRegistrar {

  static final String BUILD_INFO = "META-INF/build-info.properties";

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.resources().registerPattern(BUILD_INFO);
    new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), RefusalBody.class);
  }
}
