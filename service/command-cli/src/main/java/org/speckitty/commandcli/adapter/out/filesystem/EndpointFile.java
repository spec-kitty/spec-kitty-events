package org.speckitty.commandcli.adapter.out.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code <state dir>/endpoint.json}: where the launcher finds a running service (LAUNCHER.md "Single instance"). It is published by
 * writing a temporary file (exclusive create, owner-only), forcing it to disk and renaming it into place, so a reader sees the old file or
 * the whole new one. It is removed at exit only while it still names this instance, so a newer instance's file is never deleted.
 */
public final class EndpointFile {

  /** The endpoint file's name in the state directory. */
  public static final String NAME = "endpoint.json";

  /** Core's reader refuses a larger file (core {@code ENDPOINT_MAX_BYTES}). */
  public static final int MAX_BYTES = 64 * 1024;

  private final StateDirectory directory;
  private final JsonMapper mapper;

  public EndpointFile(StateDirectory directory, JsonMapper mapper) {
    this.directory = directory;
    this.mapper = mapper;
  }

  public Path path() {
    return directory.resolve(NAME);
  }

  /** The JSON text published for {@code document}: one object, keys in the contract's order, one trailing newline. */
  public String render(EndpointDocument document) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema_version", EndpointDocument.SCHEMA_VERSION);
    fields.put("host", EndpointDocument.HOST);
    fields.put("port", document.port());
    fields.put("token", document.token());
    fields.put("instance_id", document.instanceId());
    fields.put("pid", document.pid());
    fields.put("protocol_versions", document.protocolVersions());
    fields.put("started_at", document.startedAt());
    return mapper.writeValueAsString(fields) + "\n";
  }

  /** Publishes {@code document}: temporary file, fsync, atomic rename over {@code endpoint.json}, then a best-effort directory sync. */
  public void publish(EndpointDocument document) throws IOException {
    byte[] bytes = render(document).getBytes(StandardCharsets.UTF_8);
    Path temporary = directory.resolve(NAME + "." + document.instanceId() + ".tmp");
    try (FileChannel channel =
        FileChannel.open(
            temporary,
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            OwnerOnly.fileAttributes(temporary))) {
      OwnerOnly.restrict(temporary, false);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    } catch (IOException e) {
      Files.deleteIfExists(temporary);
      throw e;
    }
    try {
      Files.move(temporary, path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.deleteIfExists(temporary); // never leave a temporary file behind, whatever failed
      throw e;
    }
    syncDirectory();
  }

  /** Removes the endpoint file if, and only if, it names {@code instanceId}; answers whether it removed it. */
  public boolean removeIfOwn(String instanceId) {
    Path file = path();
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        bytes = in.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length > MAX_BYTES) {
        return false;
      }
      JsonNode node = mapper.readTree(bytes);
      JsonNode named = node == null ? null : node.get("instance_id");
      if (named == null || !named.isString() || !instanceId.equals(named.asString())) {
        return false;
      }
      Files.delete(file);
      syncDirectory();
      return true;
    } catch (NoSuchFileException gone) {
      return false;
    } catch (IOException | RuntimeException unreadable) {
      return false; // never delete what cannot be proven to be ours
    }
  }

  private void syncDirectory() {
    if (!OwnerOnly.posix(directory.path())) {
      return;
    }
    try (FileChannel channel = FileChannel.open(directory.path(), StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException | RuntimeException unsupported) {
      // a directory sync is best effort: the rename is already atomic
    }
  }
}
