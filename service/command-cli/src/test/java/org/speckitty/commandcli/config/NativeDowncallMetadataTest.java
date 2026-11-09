package org.speckitty.commandcli.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Guards the three FlockLedgerLock.Native descriptors against native metadata drift. */
class NativeDowncallMetadataTest {
  @Test
  void theCommandCliImageRegistersOpenFlockAndClose() throws Exception {
    String location = "META-INF/native-image/org.speckitty/command-cli/reachability-metadata.json";
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(location)) {
      assertThat(input).as(location).isNotNull();
      JsonNode calls = JsonMapper.builder().build().readTree(input).required("foreign").required("downcalls");
      assertThat(calls).hasSize(3);
      assertCall(calls.get(0), "jint", "[\"void*\",\"jint\",\"jint\"]", 2, true);
      assertCall(calls.get(1), "jint", "[\"jint\",\"jint\"]", null, true);
      assertCall(calls.get(2), "jint", "[\"jint\"]", null, false);
    }
  }

  private static void assertCall(JsonNode call, String returnType, String parameters, Integer variadic, boolean capture) {
    assertThat(call.required("returnType").asString()).isEqualTo(returnType);
    assertThat(call.required("parameterTypes").toString()).isEqualTo(parameters);
    assertThat(call.path("options").path("firstVariadicArg").isNumber() ? call.path("options").path("firstVariadicArg").asInt() : null)
        .isEqualTo(variadic);
    assertThat(call.path("options").path("captureCallState").asBoolean(false)).isEqualTo(capture);
  }
}
