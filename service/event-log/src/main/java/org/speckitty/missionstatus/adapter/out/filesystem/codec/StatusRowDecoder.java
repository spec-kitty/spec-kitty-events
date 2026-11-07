package org.speckitty.missionstatus.adapter.out.filesystem.codec;

/** Public access to the same Python-shaped value tree used by the status-row encoder. */
public final class StatusRowDecoder {

  private StatusRowDecoder() {}

  /** Parses exactly one JSON value, throwing an unchecked exception for invalid text. */
  public static Object parse(String line) {
    return PythonJson.parse(line);
  }
}
