package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;

/** A single locked status-log transaction. It accepts encoded bytes, never rows, and appends at most once. */
public interface StatusLogSession {

  /** Returns the whole current log, or an empty array when the file is missing or empty. A torn tail is refused unchanged. */
  byte[] currentBytes() throws IOException;

  /** Appends complete LF-terminated lines in one write and syncs them before returning. */
  void appendAndSync(byte[] bytes) throws IOException;
}
