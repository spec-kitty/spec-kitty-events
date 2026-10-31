package org.speckitty.commandcli.adapter.out.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.commandcli.domain.service.InstanceId;
import org.speckitty.commandcli.domain.service.WriteToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The endpoint file as core's reader enforces it (core {@code src/specify_cli/status/write_service_protocol.py}
 * {@code read_endpoint_file} and {@code _refuse_exposed}, lines 530-649 at d75e0bc5fe): a regular owner-only file in an owner-only
 * directory, at most 64 KiB, an object whose fields have the types and values below. Each rule is asserted here, in Java, against the
 * file the service publishes.
 */
@Tag("native")
class EndpointFileTest {

  @TempDir Path gcd;

  private final JsonMapper mapper = JsonMapper.builder().build();
  private StateDirectory directory;
  private EndpointFile endpoint;
  private InstanceId instance;
  private WriteToken token;

  @BeforeEach
  void prepare() throws Exception {
    directory = StateDirectory.prepare(gcd);
    endpoint = new EndpointFile(directory, mapper);
    instance = InstanceId.mint(System.currentTimeMillis(), new SecureRandom());
    token = WriteToken.mint(new SecureRandom());
  }

  private EndpointDocument document(InstanceId id, int port) {
    return new EndpointDocument(port, token.reveal(), id.value(), ProcessHandle.current().pid(), List.of(1), OffsetDateTime.now().toString());
  }

  @Test
  void publishesEveryFieldCoresReaderRequires() throws Exception {
    endpoint.publish(document(instance, 51234));

    byte[] bytes = Files.readAllBytes(endpoint.path());
    assertThat(bytes.length).isLessThanOrEqualTo(EndpointFile.MAX_BYTES);
    JsonNode file = mapper.readTree(bytes);
    assertThat(file.isObject()).isTrue();
    assertThat(file.get("schema_version").isInt()).isTrue();
    assertThat(file.get("schema_version").asInt()).isEqualTo(1);
    assertThat(file.get("host").asString()).isEqualTo("127.0.0.1");
    assertThat(file.get("port").isInt()).isTrue();
    assertThat(file.get("port").asInt()).isEqualTo(51234);
    assertThat(file.get("token").asString()).hasSizeGreaterThanOrEqualTo(43).matches("[A-Za-z0-9_-]+").isEqualTo(token.reveal());
    assertThat(file.get("instance_id").asString()).isEqualTo(instance.value());
    assertThat(file.get("pid").isIntegralNumber()).isTrue();
    assertThat(file.get("pid").asLong()).isEqualTo(ProcessHandle.current().pid());
    assertThat(file.get("protocol_versions").isArray()).isTrue();
    assertThat(file.get("protocol_versions").get(0).asInt()).isEqualTo(1);
    assertThat(OffsetDateTime.parse(file.get("started_at").asString())).isNotNull();
    assertThat(new String(bytes, StandardCharsets.UTF_8)).endsWith("}\n");
  }

  @Test
  void theFileIsARegularOwnerOnlyFileInAnOwnerOnlyDirectory() throws Exception {
    endpoint.publish(document(instance, 51234));

    assertThat(Files.isRegularFile(endpoint.path(), LinkOption.NOFOLLOW_LINKS)).isTrue();
    assumeTrue(OwnerOnly.posix(gcd));
    Set<PosixFilePermission> file = Files.getPosixFilePermissions(endpoint.path(), LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> dir = Files.getPosixFilePermissions(directory.path(), LinkOption.NOFOLLOW_LINKS);
    assertThat(file).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    assertThat(dir).allMatch(p -> p.name().startsWith("OWNER_"));
    assertThat(Files.getOwner(endpoint.path())).isEqualTo(Files.getOwner(gcd));
  }

  @Test
  void aSecondPublishReplacesTheFileWholeAndLeavesNoTemporaryFile() throws Exception {
    endpoint.publish(document(instance, 51234));
    InstanceId next = InstanceId.mint(System.currentTimeMillis(), new SecureRandom());

    endpoint.publish(document(next, 40000));

    assertThat(mapper.readTree(Files.readAllBytes(endpoint.path())).get("instance_id").asString()).isEqualTo(next.value());
    try (Stream<Path> files = Files.list(directory.path())) {
      assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
    }
  }

  @Test
  void removesTheFileOnlyWhileItNamesThisInstance() throws Exception {
    InstanceId newer = InstanceId.mint(System.currentTimeMillis(), new SecureRandom());
    endpoint.publish(document(newer, 40000));

    assertThat(endpoint.removeIfOwn(instance.value())).isFalse();
    assertThat(endpoint.path()).exists();

    assertThat(endpoint.removeIfOwn(newer.value())).isTrue();
    assertThat(endpoint.path()).doesNotExist();
    assertThat(endpoint.removeIfOwn(newer.value())).isFalse();
  }

  @Test
  void leavesAFileItCannotReadAsItsOwn() throws Exception {
    Files.writeString(endpoint.path(), "not json");
    assertThat(endpoint.removeIfOwn(instance.value())).isFalse();

    Files.writeString(endpoint.path(), "{\"instance_id\": 7}");
    assertThat(endpoint.removeIfOwn(instance.value())).isFalse();

    Files.write(endpoint.path(), new byte[EndpointFile.MAX_BYTES + 1]);
    assertThat(endpoint.removeIfOwn(instance.value())).isFalse();
    assertThat(endpoint.path()).exists();
  }

  @Test
  void neverFollowsALinkPlantedInThePlaceOfTheFile() throws Exception {
    Path target = Files.writeString(gcd.resolve("victim.json"), "{\"instance_id\":\"" + instance.value() + "\"}");
    try {
      Files.createSymbolicLink(endpoint.path(), target);
    } catch (UnsupportedOperationException | java.io.IOException noLinks) {
      assumeTrue(false, "symbolic links are not available here");
    }

    assertThat(endpoint.removeIfOwn(instance.value())).isFalse();
    assertThat(target).exists();
  }

  @Test
  void theDocumentRefusesAPortOutsideItsRangeAndHidesTheToken() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> document(instance, 0)).isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> document(instance, 65536)).isInstanceOf(IllegalArgumentException.class);
    assertThat(document(instance, 1).toString()).doesNotContain(token.reveal());
  }
}
