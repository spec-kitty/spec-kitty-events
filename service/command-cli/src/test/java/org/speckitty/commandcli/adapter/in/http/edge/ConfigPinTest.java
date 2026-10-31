package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * The configuration pin, copied from the read service: only the packaged application.properties is read, so a file planted in the working
 * directory, an extra location or an import cannot reconfigure the service; static serving stays off; and the bind guard keeps 127.0.0.1.
 */
class ConfigPinTest {

  @Test
  void theConfigurationIsPinnedToThePackagedFileAndTheBindToLoopback() {
    try (EdgeRig rig = EdgeRig.start("--spring.config.additional-location=file:/tmp/", "--spring.config.import=optional:file:/tmp/x.properties")) {
      ConfigurableEnvironment environment = rig.context().getEnvironment();

      assertThat(environment.getPropertySources().contains(ConfigPin.SOURCE_NAME)).isTrue();
      assertThat(environment.getProperty("spring.config.location")).isEqualTo(ConfigPin.LOCATION);
      assertThat(environment.getProperty("spring.config.additional-location")).isEmpty();
      assertThat(environment.getProperty("spring.config.import")).isEmpty();
      assertThat(environment.getProperty("spring.web.resources.add-mappings")).isEqualTo("false");
      assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
      assertThat(environment.getProperty("server.port")).isEqualTo("0");
    }
  }
}
