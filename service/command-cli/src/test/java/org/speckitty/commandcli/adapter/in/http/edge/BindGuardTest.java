package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The loopback-only bind, copied from the read service's BindGuardTest: only the literal 127.0.0.1 passes. */
@Tag("native")
class BindGuardTest {

  @Test
  void theLoopbackLiteralPasses() {
    assertThat(BindGuard.refusal("127.0.0.1")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.0.0.0", "::", "::1", "localhost", "192.168.1.10", "example.com", " 127.0.0.1"})
  void everyOtherAddressIsRefusedNamingIt(String address) {
    assertThat(BindGuard.refusal(address)).hasValueSatisfying(reason -> assertThat(reason).contains("127.0.0.1 only"));
  }

  @Test
  void anUnsetOrBlankAddressIsRefused() {
    assertThat(BindGuard.refusal(null)).hasValueSatisfying(reason -> assertThat(reason).contains("unset"));
    assertThat(BindGuard.refusal("  ")).hasValueSatisfying(reason -> assertThat(reason).contains("unset"));
  }

  @Test
  void theConnectorMustBeBoundToLoopback() throws Exception {
    assertThat(BindGuard.connectorRefusal(InetAddress.getByName("127.0.0.1"))).isEmpty();
    assertThat(BindGuard.connectorRefusal(null)).isPresent();
    assertThat(BindGuard.connectorRefusal(InetAddress.getByName("192.168.1.10"))).isPresent();
  }
}
