package org.speckitty.commandcli.application.lifecycle;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.speckitty.commandcli.domain.service.InstanceId;
import org.speckitty.commandcli.domain.service.ProtocolSupport;
import org.speckitty.commandcli.domain.service.RepositoryId;

/**
 * What the running service is (contracts/mission-write Handshake): the protocol majors it speaks, its build version, the instance it
 * minted at start and the clone it serves. The launcher checks the instance and the clone before it trusts the service.
 */
public record ServiceIdentity(InstanceId instanceId, RepositoryId repositoryId, String serviceVersion) {

  /** The Handshake.serviceVersion pattern. */
  public static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$");

  public ServiceIdentity {
    Objects.requireNonNull(instanceId, "instanceId");
    Objects.requireNonNull(repositoryId, "repositoryId");
    Objects.requireNonNull(serviceVersion, "serviceVersion");
    if (!VERSION.matcher(serviceVersion).matches()) {
      throw new IllegalArgumentException("the service version does not match the Handshake pattern");
    }
  }

  public List<Integer> protocolVersions() {
    return ProtocolSupport.SUPPORTED;
  }
}
