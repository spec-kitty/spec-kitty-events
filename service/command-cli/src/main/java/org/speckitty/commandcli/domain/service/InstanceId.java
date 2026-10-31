package org.speckitty.commandcli.domain.service;

import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * The ULID a service instance mints at start and publishes in its endpoint file, its handshake and its health
 * (contracts/mission-write schemas Handshake.instanceId and Health.instanceId): 26 Crockford base-32 characters, the first ten
 * encoding the 48-bit millisecond time, the last sixteen eighty random bits.
 */
public record InstanceId(String value) {

  public static final Pattern PATTERN = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

  private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final long MAX_TIME = (1L << 48) - 1;

  public InstanceId {
    Objects.requireNonNull(value, "value");
    if (!PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("not a ULID");
    }
  }

  /** Mints a ULID for the given epoch milliseconds from the given random source. */
  public static InstanceId mint(long epochMillis, RandomGenerator random) {
    if (epochMillis < 0 || epochMillis > MAX_TIME) {
      throw new IllegalArgumentException("time outside the ULID range");
    }
    char[] text = new char[26];
    long time = epochMillis;
    for (int i = 9; i >= 0; i--) {
      text[i] = CROCKFORD[(int) (time & 31)];
      time >>>= 5;
    }
    for (int i = 10; i < 26; i++) {
      text[i] = CROCKFORD[random.nextInt(32)];
    }
    return new InstanceId(new String(text));
  }

  @Override
  public String toString() {
    return value;
  }
}
