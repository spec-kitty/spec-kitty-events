package org.speckitty.commandcli.domain.service;

import java.util.List;

/**
 * The protocol majors this service speaks (contracts/mission-write ProtocolVersion, header Spec-Kitty-Write-Protocol; core
 * {@code SUPPORTED_PROTOCOL_VERSIONS = (1,)}). A header value is accepted only as a plain decimal integer naming a supported major:
 * no sign, no spaces, no leading zeros.
 */
public final class ProtocolSupport {

  /** The supported majors, ascending, as the handshake and the endpoint file list them. */
  public static final List<Integer> SUPPORTED = List.of(1);

  private ProtocolSupport() {}

  /** True when {@code header} is exactly one supported major. */
  public static boolean accepts(String header) {
    if (header == null || header.isEmpty() || header.length() > 9) {
      return false;
    }
    if (header.length() > 1 && header.charAt(0) == '0') {
      return false;
    }
    for (int i = 0; i < header.length(); i++) {
      char c = header.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return SUPPORTED.contains(Integer.parseInt(header));
  }

  /** The detail of a PROTOCOL_VERSION_UNSUPPORTED refusal, listing the supported majors. */
  public static String supportedDetail() {
    StringBuilder text = new StringBuilder("Supported protocol versions: ");
    for (int i = 0; i < SUPPORTED.size(); i++) {
      text.append(i == 0 ? "" : ", ").append(SUPPORTED.get(i));
    }
    return text.append('.').toString();
  }
}
