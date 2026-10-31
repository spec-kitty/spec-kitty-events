package org.speckitty.commandcli.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The identity values of a service instance and the vocabulary the contract fixes. */
@Tag("native")
class DomainValuesTest {

  @Test
  void aMintedInstanceIdIsAUlidWhoseTimeIsTheGivenMillisecond() {
    InstanceId id = InstanceId.mint(0x0123_4567_89ABL, new SplittableRandom(7));

    assertThat(id.value()).matches(InstanceId.PATTERN);
    assertThat(id.value()).startsWith("014D2PF2DB"); // 0x0123456789AB in Crockford base 32, ten characters
    assertThat(id).hasToString(id.value());
  }

  @Test
  void instanceIdsDifferBetweenStarts() {
    Set<String> ids = new HashSet<>();
    SecureRandom random = new SecureRandom();
    for (int i = 0; i < 1000; i++) {
      ids.add(InstanceId.mint(1_700_000_000_000L, random).value());
    }
    assertThat(ids).hasSize(1000);
  }

  @Test
  void anInstanceIdRefusesTextThatIsNotAUlidAndTimesOutsideItsRange() {
    assertThatThrownBy(() -> new InstanceId("01M694HJ0000000000000000AI")).isInstanceOf(IllegalArgumentException.class); // I is not Crockford
    assertThatThrownBy(() -> new InstanceId("short")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InstanceId.mint(-1, new SplittableRandom())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InstanceId.mint(1L << 48, new SplittableRandom())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theRepositoryIdIsALowercaseHexSha256() {
    String hex = "6b4ca2db35cfaf43d6963005a8445402c4ea6f70b038086ad0d6decda64670c0";

    assertThat(new RepositoryId(hex).value()).isEqualTo(hex);
    assertThat(new RepositoryId(hex)).hasToString(hex);
    assertThatThrownBy(() -> new RepositoryId("ABC")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RepositoryId(hex.toUpperCase())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aTokenIs43UrlSafeCharactersAndMatchesOnlyItself() {
    WriteToken token = WriteToken.mint(new SecureRandom());
    WriteToken other = WriteToken.mint(new SecureRandom());

    assertThat(token.reveal()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    assertThat(token.matches(token.reveal())).isTrue();
    assertThat(token.matches(other.reveal())).isFalse();
    assertThat(token.matches(token.reveal() + "x")).isFalse();
    assertThat(token.matches("")).isFalse();
    assertThat(token.matches(null)).isFalse();
    assertThat(token).isEqualTo(token).isNotEqualTo(other).isNotEqualTo("text");
    assertThat(token.hashCode()).isEqualTo(other.hashCode());
  }

  @Test
  void aTokenNeverShowsInItsTextForm() {
    WriteToken token = WriteToken.mint(new SecureRandom());

    assertThat(token.toString()).doesNotContain(token.reveal()).isEqualTo("WriteToken[redacted]");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1"})
  void theProtocolHeaderAcceptsASupportedMajor(String header) {
    assertThat(ProtocolSupport.accepts(header)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0", "2", "01", "+1", "-1", " 1", "1 ", "1.0", "one", "9999999999", "١"})
  void theProtocolHeaderRefusesAnythingElse(String header) {
    assertThat(ProtocolSupport.accepts(header)).isFalse();
  }

  @Test
  void theProtocolDetailListsTheSupportedMajors() {
    assertThat(ProtocolSupport.accepts(null)).isFalse();
    assertThat(ProtocolSupport.supportedDetail()).isEqualTo("Supported protocol versions: 1.");
    assertThat(ProtocolSupport.SUPPORTED).containsExactly(1);
  }

  @Test
  void theServiceStateOnlyMovesForward() {
    assertThat(ServiceState.READY.canMoveTo(ServiceState.DRAINING)).isTrue();
    assertThat(ServiceState.DRAINING.canMoveTo(ServiceState.STOPPED)).isTrue();
    assertThat(ServiceState.READY.canMoveTo(ServiceState.STOPPED)).isTrue();
    assertThat(ServiceState.DRAINING.canMoveTo(ServiceState.READY)).isFalse();
    assertThat(ServiceState.DRAINING.canMoveTo(ServiceState.DRAINING)).isFalse();
    assertThat(ServiceState.READY.wireValue()).isEqualTo("ready");
    assertThat(ServiceState.DRAINING.wireValue()).isEqualTo("draining");
    assertThat(ServiceState.STOPPED.wireValue()).isEqualTo("draining");
  }

  @Test
  void eachRefusalCodeCarriesTheStatusTheContractBindsIt() {
    assertThat(RefusalCode.REQUEST_REFUSED.status()).isEqualTo(403);
    assertThat(RefusalCode.WRITE_TOKEN_INVALID.status()).isEqualTo(401);
    assertThat(RefusalCode.PROTOCOL_VERSION_UNSUPPORTED.status()).isEqualTo(400);
    assertThat(RefusalCode.REQUEST_TOO_LARGE.status()).isEqualTo(413);
    assertThat(RefusalCode.REQUEST_MALFORMED.status()).isEqualTo(400);
    assertThat(RefusalCode.SERVICE_DRAINING.status()).isEqualTo(503);
    assertThat(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED.status()).isEqualTo(500);
    for (RefusalCode code : RefusalCode.values()) {
      assertThat(code.title()).isNotBlank();
    }
  }
}
