package org.speckitty.missionstatus.architecture.fixture.statuslog;

import tools.jackson.core.json.JsonFactory;

/** Planted violation: the byte appender must not parse rows or depend on Jackson. */
public final class RowParsingAppender {

  private RowParsingAppender() {}

  public static JsonFactory parser() {
    return new JsonFactory();
  }
}
