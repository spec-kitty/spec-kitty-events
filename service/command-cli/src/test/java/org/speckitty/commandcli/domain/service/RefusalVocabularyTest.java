package org.speckitty.commandcli.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RefusalBody;
import org.speckitty.commandcli.adapter.in.http.operations.ContractSchemas;
import tools.jackson.databind.json.JsonMapper;

/** The refusal vocabulary and its contract bindings, read from the split mission-write schemas. */
class RefusalVocabularyTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void everyCodeIsAContractCodeWithTheStatusWriteRefusalBindsIt() {
    List<String> contractCodes = ContractSchemas.constants("WriteRefusalCode");
    assertThat(Arrays.stream(RefusalCode.values()).map(Enum::name)).allMatch(contractCodes::contains);
    for (RefusalCode code : RefusalCode.values()) {
      String valid = body(RefusalBody.of(code).bytes(JSON));
      assertThat(ContractSchemas.violations("WriteRefusal", valid)).as(code.name()).isEmpty();
      String wrongStatus = "{\"code\":\"" + code.name() + "\",\"status\":" + (code.status() == 500 ? 501 : 500) + ",\"title\":\"x\"}";
      assertThat(ContractSchemas.violations("WriteRefusal", wrongStatus)).as(code.name() + " with another status").isNotEmpty();
    }
  }

  @Test
  void everyReasonIsOneOfTheFifteenContractUrnsWithItsBoundCode() {
    List<String> contractReasons = ContractSchemas.constants("WriteRefusalReason");
    assertThat(contractReasons).hasSize(15);
    assertThat(Arrays.stream(RefusalReason.values()).map(RefusalReason::urn))
        .containsExactlyInAnyOrderElementsOf(contractReasons);
    for (RefusalReason reason : RefusalReason.values()) {
      RefusalCode expectedCode =
          switch (reason) {
            case DEPENDENCIES_UNSATISFIED -> RefusalCode.DEPENDENCIES_NOT_SATISFIED;
            case CLAIM_HELD_BY_OTHER_ACTOR -> RefusalCode.WP_ALREADY_CLAIMED;
            default -> RefusalCode.TRANSITION_REJECTED;
          };
      assertThat(reason.code()).as(reason.urn()).isEqualTo(expectedCode);
      RefusalBody refusal = RefusalBody.of(expectedCode, "A fixed detail.").withType(reason.urn());
      assertThat(ContractSchemas.violations("WriteRefusal", body(refusal.bytes(JSON)))).as(reason.urn()).isEmpty();
    }
  }

  private static String body(byte[] bytes) {
    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
  }
}
