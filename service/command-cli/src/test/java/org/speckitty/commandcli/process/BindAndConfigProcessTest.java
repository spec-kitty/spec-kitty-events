package org.speckitty.commandcli.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The read service's process-level BindGuard and ConfigPin tests, for the write service (deferred from WP03): an environment that asks for
 * another bind address makes the process exit 1 before it publishes anything, and an application.properties planted in the working directory
 * is never read.
 */
@Tag("process")
class BindAndConfigProcessTest {

  @TempDir Path gitCommonDir;
  @TempDir Path workingDirectory;

  private List<String> serve() {
    List<String> command = new ArrayList<>(ServiceProcess.argvPrefix());
    command.addAll(List.of("serve", "--git-common-dir", gitCommonDir.toString()));
    return command;
  }

  @Test
  void anEnvironmentAskingForAllInterfacesExitsOneAndPublishesNothing() throws Exception {
    Files.createDirectory(ServiceProcess.stateDirectory(gitCommonDir));
    try (ServiceProcess service = ServiceProcess.start(gitCommonDir, serve(), Map.of("SERVER_ADDRESS", "0.0.0.0"), null)) {
      assertThat(service.awaitExit(Duration.ofSeconds(30))).isTrue();
      assertThat(service.process.exitValue()).isEqualTo(1);
      assertThat(service.endpointFile()).doesNotExist();
      assertThat(service.log()).contains("127.0.0.1 only");
    }
  }

  @Test
  void anApplicationPropertiesFilePlantedInTheWorkingDirectoryIsNotRead() throws Exception {
    Files.writeString(workingDirectory.resolve("application.properties"), "server.address=0.0.0.0\nserver.port=1\n");
    Files.createDirectories(workingDirectory.resolve("config"));
    Files.writeString(workingDirectory.resolve("config").resolve("application.properties"), "server.address=0.0.0.0\n");
    Files.createDirectory(ServiceProcess.stateDirectory(gitCommonDir));
    try (ServiceProcess service = ServiceProcess.start(gitCommonDir, serve(), Map.of(), workingDirectory)) {
      JsonNode endpoint = LauncherContractProcessTest.assertReady(service);
      assertThat(endpoint.get("port").asInt()).isNotEqualTo(1);
      assertThat(ServiceProcess.shutdown(endpoint).status()).isEqualTo(202);
      assertThat(service.awaitExit(Duration.ofSeconds(10))).isTrue();
    }
  }
}
