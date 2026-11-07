package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.service.RefusalReason;

class DetailPolicyTest {
  @Test
  void everyReasonHasNonEchoingDetail() {
    for (RefusalReason reason : RefusalReason.values()) {
      boolean echoes = reason == RefusalReason.UNKNOWN_LANE || reason == RefusalReason.FORCE_NOTE_REQUIRED
          || reason == RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR || reason == RefusalReason.START_REJECTED;
      String coreMessage = echoes ? "WP99 secret actor/token/path" : "Transition requires actor identity";
      String detail = DetailPolicy.detail(reason, coreMessage);
      assertThat(detail).isNotBlank().doesNotContain("secret").doesNotContain("WP99");
      if (!echoes) assertThat(detail).isEqualTo(coreMessage);
    }
  }

  @Test
  void laneVocabularyMessagesRemainVerbatim() {
    assertThat(DetailPolicy.detail(RefusalReason.ILLEGAL_TRANSITION, "Illegal transition: done -> planned"))
        .isEqualTo("Illegal transition: done -> planned");
    assertThat(DetailPolicy.detail(RefusalReason.UNKNOWN_LANE, "Unknown lane: secret"))
        .isEqualTo("The requested lane is unknown.");
    assertThat(DetailPolicy.detail(RefusalReason.FORCE_NOTE_REQUIRED, "WP99 secret"))
        .isEqualTo("A forced review-lane exit requires a non-blank note.");
  }
}
