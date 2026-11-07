package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.file.Path;

/** A status log does not end in LF. It is refused unchanged; this writer never repairs it. */
public final class TornTailException extends IOException {

  private static final long serialVersionUID = 1L;

  TornTailException(Path log) {
    super("status log " + log + " has a torn tail; append refused");
  }
}
