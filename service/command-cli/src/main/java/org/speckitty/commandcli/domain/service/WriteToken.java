package org.speckitty.commandcli.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The bearer token of one service instance (contracts/mission-write security scheme writeToken; core ADR 2026-10-27-1 D-2): 32 random
 * bytes, URL-safe base64 without padding (43 characters), new at every start. It lives in memory and in the owner-only endpoint file
 * only; {@link #toString()} never reveals it and comparison takes the same time whatever the presented value.
 */
public final class WriteToken {

  /** Random bytes per token. */
  public static final int BYTES = 32;

  private final String value;

  private WriteToken(String value) {
    this.value = value;
  }

  /** A fresh token from {@code random}, which must be a cryptographically strong source in production. */
  public static WriteToken mint(RandomGenerator random) {
    byte[] bytes = new byte[BYTES];
    random.nextBytes(bytes);
    return new WriteToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
  }

  /** The token text, for the endpoint file only. */
  public String reveal() {
    return value;
  }

  /** True when {@code presented} is this token; constant time in the length of the token. */
  public boolean matches(String presented) {
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII), presented.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WriteToken token && MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII), token.value.getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  public int hashCode() {
    return Objects.hash(BYTES);
  }

  @Override
  public String toString() {
    return "WriteToken[redacted]";
  }
}
