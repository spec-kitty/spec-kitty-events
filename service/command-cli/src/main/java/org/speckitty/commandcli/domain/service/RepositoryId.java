package org.speckitty.commandcli.domain.service;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The identity of the clone a service serves: the lowercase hex SHA-256 of the real path of its git common dir, written with forward
 * slashes (contracts/mission-write schema Handshake.repositoryId; core {@code write_service_protocol.repository_id}, which hashes
 * {@code Path.resolve().as_posix()}). The launcher refuses a service whose identity differs from its own digest. The digest is taken by
 * the filesystem adapter, which resolves the path; this value only guarantees the shape.
 */
public record RepositoryId(String value) {

  public static final Pattern PATTERN = Pattern.compile("^[0-9a-f]{64}$");

  public RepositoryId {
    Objects.requireNonNull(value, "value");
    if (!PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("not a lowercase hex SHA-256");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
