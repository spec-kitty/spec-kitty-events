package org.speckitty.commandcli.adapter.out.filesystem;

import java.util.List;

/**
 * The content of {@code endpoint.json} (core ADR 2026-10-27-1 D-2; core {@code write_service_protocol.read_endpoint_file}):
 * {@code schema_version} 1, {@code host} 127.0.0.1, the bound port, the token, the instance id, the pid, the protocol majors and the
 * publish time (ISO-8601 with an offset).
 */
public record EndpointDocument(int port, String token, String instanceId, long pid, List<Integer> protocolVersions, String startedAt) {

  public static final int SCHEMA_VERSION = 1;
  public static final String HOST = "127.0.0.1";

  public EndpointDocument {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port outside 1..65535");
    }
    protocolVersions = List.copyOf(protocolVersions);
  }

  @Override
  public String toString() {
    return "EndpointDocument[port=" + port + ", instanceId=" + instanceId + ", pid=" + pid + ", token=redacted]";
  }
}
