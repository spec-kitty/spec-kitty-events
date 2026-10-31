package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.service.RefusalCode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The refusal body's bytes, and that writing them needs no reflection. The filters serialise it outside any controller, so nothing
 * registers the record for the native image; bean introspection of it fails there (the first native CI run of #156: 46 native-tag
 * tests). It is therefore written as a map whose values are only JDK strings and integers.
 */
@Tag("native")
class RefusalBodyTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void theBytesAreTheCompactMembersInOrderWithOnlyTheSetOnesAndOneNewline() {
    assertThat(new String(RefusalBody.of(RefusalCode.WRITE_TOKEN_INVALID).bytes(mapper), StandardCharsets.UTF_8))
        .isEqualTo("{\"code\":\"WRITE_TOKEN_INVALID\",\"status\":401,\"title\":\"" + RefusalCode.WRITE_TOKEN_INVALID.title() + "\"}\n");
    assertThat(new String(RefusalBody.of(RefusalCode.REQUEST_MALFORMED, "d").withInstance("/rows/0").bytes(mapper), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"code\":\"REQUEST_MALFORMED\",\"detail\":\"d\",\"instance\":\"/rows/0\",\"status\":400,\"title\":\""
                + RefusalCode.REQUEST_MALFORMED.title()
                + "\"}\n");
  }

  @Test
  void theSerialisedFormHoldsOnlyJdkValuesSoNoRecordIntrospectionIsNeeded() {
    RefusalBody body = RefusalBody.of(RefusalCode.REQUEST_MALFORMED, "d").withInstance("/rows/0");
    assertThat(body.members().values()).allSatisfy(value -> assertThat(value).isInstanceOfAny(String.class, Integer.class));
  }
}
