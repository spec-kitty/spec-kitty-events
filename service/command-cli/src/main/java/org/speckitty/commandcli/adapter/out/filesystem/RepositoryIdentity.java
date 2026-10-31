package org.speckitty.commandcli.adapter.out.filesystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.speckitty.commandcli.domain.service.RepositoryId;

/**
 * Resolves the git common dir to the identity core's launcher expects: SHA-256 of {@code Path.resolve().as_posix()} (core
 * {@code write_service_protocol.repository_id}). The real path is taken with links followed and its separators made forward slashes, so
 * a Windows path {@code C:\\repo\\.git} hashes as {@code C:/repo/.git}, as Python's {@code as_posix()} writes it.
 */
public final class RepositoryIdentity {

  private RepositoryIdentity() {}

  public static RepositoryId of(Path gitCommonDir) throws IOException {
    return ofRealPath(posixRealPath(gitCommonDir));
  }

  /** The identity of the git common dir whose resolved path, with forward slashes, is {@code posixRealPath}. */
  static RepositoryId ofRealPath(String posixRealPath) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(posixRealPath.getBytes(StandardCharsets.UTF_8));
      return new RepositoryId(HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
    }
  }

  static String posixRealPath(Path gitCommonDir) throws IOException {
    return gitCommonDir.toRealPath().toString().replace('\\', '/');
  }
}
