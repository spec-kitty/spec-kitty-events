package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import tools.jackson.core.StreamReadConstraints;

/** Public access to the same Python-shaped value tree used by the status-row encoder. */
public final class StatusRowDecoder {

  /** The nesting depth and integer length the reader accepts, set explicitly on its parser (never a JVM-wide default). */
  public static StreamReadConstraints readConstraints() {
    return PythonJson.constraints();
  }

  private StatusRowDecoder() {}

  /** Parses exactly one JSON value, throwing an unchecked exception for invalid text. */
  public static Object parse(String line) {
    return PythonJson.parse(line);
  }
}
