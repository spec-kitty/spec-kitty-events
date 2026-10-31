package org.speckitty.commandcli.adapter.in.http.operations;

import java.util.List;

/**
 * The two success bodies of the Service operations, written by hand to the contract's schemas (contracts/mission-write schemas Handshake and
 * Health); the conformance tests validate both against those schemas. Member names are the contract's.
 */
final class ServiceBodies {

  private ServiceBodies() {}

  /** Handshake: the protocol majors (ascending), the build version, the instance and the repository identity. */
  record Handshake(List<Integer> protocolVersions, String serviceVersion, String instanceId, String repositoryId) {}

  /** Health: {@code ready} or {@code draining}, and the instance. */
  record Health(String status, String instanceId) {}
}
