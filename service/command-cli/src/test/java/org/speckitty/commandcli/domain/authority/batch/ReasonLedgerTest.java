package org.speckitty.commandcli.domain.authority.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.authority.CorpusAuthorityCases;
import org.speckitty.commandcli.domain.service.RefusalReason;
import tools.jackson.databind.ObjectMapper;

/** FR-012: every refusal reason is either exercised by a domain case or explicitly unreachable over the wire. */
class ReasonLedgerTest {
  @Test
  void everyReasonHasExactlyOneLedgerExplanation() throws IOException {
    List<CorpusAuthorityCases.Case> cases = CorpusAuthorityCases.load();
    Map<String, Object> unreachable = unreachable();
    Map<String, Object> partial = unreachable.get("partial") instanceof Map<?, ?> values ? cast(values) : Map.of();
    Map<String, String> ledger = new LinkedHashMap<>();
    for (RefusalReason reason : RefusalReason.values()) {
      List<String> ids = cases.stream()
          .filter(testCase -> reason.urn().equals(testCase.expected().get("reason")))
          .map(CorpusAuthorityCases.Case::id)
          .toList();
      Object cause = unreachable.get(reason.urn());
      if (!ids.isEmpty()) {
        assertThat(cause).as(reason.urn()).isNull();
        ledger.put(reason.name(), String.join(", ", ids));
      } else {
        assertThat(cause).as(reason.urn()).isInstanceOf(String.class);
        assertThat((String) cause).as(reason.urn()).isNotBlank();
        assertThat(partial).doesNotContainKey(reason.urn());
        ledger.put(reason.name(), (String) cause);
      }
    }
    assertThat(ledger).hasSize(15);
    ledger.forEach((reason, explanation) -> System.out.printf("reason %s -> %s%n", reason, explanation));
  }

  private static Map<String, Object> unreachable() throws IOException {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      Path candidate = current.resolve("src/spec_kitty_events/conformance/status_transition_parity/unreachable.json");
      if (Files.isRegularFile(candidate)) return cast(new ObjectMapper().readValue(Files.readString(candidate), Map.class));
      current = current.getParent();
    }
    throw new IOException("status transition unreachable ledger not found");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object value) {
    return (Map<String, Object>) value;
  }
}
