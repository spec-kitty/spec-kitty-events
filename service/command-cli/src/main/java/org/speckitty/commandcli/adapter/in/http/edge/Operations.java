package org.speckitty.commandcli.adapter.in.http.edge;

import java.util.regex.Pattern;

/**
 * The request paths of the contract's operations under {@code /api/v1} (contracts/mission-write openapi.yaml, servers {@code /api/v1};
 * operator ruling of 2026-10-31: the prefix wins). The edge filters decide by path before any controller runs.
 */
public final class Operations {

  public static final String PREFIX = "/api/v1";
  public static final String HANDSHAKE = PREFIX + "/handshake";
  public static final String HEALTH = PREFIX + "/health";
  public static final String SHUTDOWN = PREFIX + "/shutdown";

  /** The three write operations: one transition unit, a batch of units, one standalone annotation. */
  private static final Pattern WRITE = Pattern.compile("^/api/v1/missions/[^/]+/(work-package-transitions(/batch)?|inner-state-annotations)$");

  private Operations() {}

  /** True for the POST paths of the three write operations. */
  public static boolean isWrite(String path) {
    return path != null && WRITE.matcher(path).matches();
  }

  /** True for the paths that require the protocol header: every operation but the handshake and the health. */
  public static boolean needsProtocol(String path) {
    return SHUTDOWN.equals(path) || isWrite(path);
  }
}
