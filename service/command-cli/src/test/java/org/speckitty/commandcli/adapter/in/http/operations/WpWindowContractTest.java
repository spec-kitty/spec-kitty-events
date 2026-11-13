package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #229: mission-write types {@code policy_metadata.wp_window} as core's status-event-wp-window contract
 * (schema_version 1) states it, closed at both levels. The service relays any value unchanged and never refuses on it
 * (W-4, W-7; the {@code wp-window-*} parity cases prove that over HTTP); this test pins only what the contract types.
 */
class WpWindowContractTest {

  private static final String SHA1 = "3f1c0d9e8b7a6f5e4d3c2b1a0f9e8d7c6b5a4f3e";
  private static final String SHA256 = "9b2e4c6a8d0f1e3c5a7b9d1f3e5c7a9b2d4f6e8a0c1b3d5f7e9a2c4b6d8f0e1a";

  private static String row(String wpWindow) {
    return "{\"event_id\":\"01M5Z7A1B2C3D4E5F6G7H8J9KM\",\"policy_metadata\":{\"lane_head\":\"" + SHA1 + "\",\"wp_window\":" + wpWindow + "}}";
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"schema_version\":1,\"open_sha\":\"" + SHA1 + "\"}",
      "{\"schema_version\":1,\"open_sha\":\"" + SHA256 + "\"}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"lane_branch_missing\",\"detail\":\"refs/heads/kitty/lane-b not found\"}}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"lane_branch_missing\"}}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"capture_failed\",\"cause\":\"git_error\",\"detail\":\"fatal: bad object\"}}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"capture_failed\",\"cause\":\"probe_error\"}}"})
  void everyShapeAWriterWritesIsTyped(String wpWindow) {
    assertThat(ContractSchemas.violations("EventRow", row(wpWindow))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"schema_version\":2,\"open_sha\":\"" + SHA1 + "\"}",
      "{\"schema_version\":true,\"open_sha\":\"" + SHA1 + "\"}",
      "{\"schema_version\":1}",
      "{\"open_sha\":\"" + SHA1 + "\"}",
      "{\"schema_version\":1,\"open_sha\":\"" + SHA1 + "\",\"gap\":{\"reason\":\"lane_branch_missing\"}}",
      "{\"schema_version\":1,\"open_sha\":\"3F1C0D9E8B7A6F5E4D3C2B1A0F9E8D7C6B5A4F3E\"}",
      "{\"schema_version\":1,\"open_sha\":\"3f1c0d9e8b7a6f5e4d3c2b1a0f9e8d7c6b5a4f3e0\"}",
      "{\"schema_version\":1,\"open_sha\":\"" + SHA1 + "\",\"recorded_at\":\"x\"}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"not_recorded\"}}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"not_on_lane\"}}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"capture_failed\"}}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"capture_failed\",\"cause\":\"timeout\"}}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"lane_branch_missing\",\"cause\":\"git_error\"}}",
      "{\"schema_version\":1,\"gap\":{\"reason\":\"capture_failed\",\"cause\":\"git_error\",\"extra\":1}}"})
  void anythingElseIsOutsideTheType(String wpWindow) {
    assertThat(ContractSchemas.violations("EventRow", row(wpWindow))).isNotEmpty();
  }

  @Test
  void detailIsCappedAt500Characters() {
    String at = "{\"schema_version\":1,\"gap\":{\"reason\":\"capture_failed\",\"cause\":\"git_error\",\"detail\":\"%s\"}}";
    assertThat(ContractSchemas.violations("EventRow", row(at.formatted("x".repeat(500))))).isEmpty();
    assertThat(ContractSchemas.violations("EventRow", row(at.formatted("x".repeat(501))))).isNotEmpty();
  }

  @Test
  void theRestOfPolicyMetadataStaysUntyped() {
    for (String metadata : List.of("null", "{}", "{\"agent\":\"claude\",\"shell_pid\":12}", "{\"future_key\":[1,2]}")) {
      assertThat(ContractSchemas.violations("EventRow", "{\"event_id\":\"01M5Z7A1B2C3D4E5F6G7H8J9KM\",\"policy_metadata\":" + metadata + "}"))
          .as(metadata).isEmpty();
    }
  }

  @Test
  void theGapVocabulariesAreClosed() {
    assertThat(values("/properties/policy_metadata/properties/wp_window/properties/gap/properties/reason/enum"))
        .containsExactly("capture_failed", "lane_branch_missing");
    assertThat(values("/properties/policy_metadata/properties/wp_window/properties/gap/properties/cause/enum"))
        .containsExactly("git_error", "probe_error");
  }

  private static List<String> values(String pointer) {
    List<String> values = new java.util.ArrayList<>();
    ContractSchemas.schema("EventRow").at(pointer).forEach(node -> values.add(node.asString()));
    return values;
  }
}
