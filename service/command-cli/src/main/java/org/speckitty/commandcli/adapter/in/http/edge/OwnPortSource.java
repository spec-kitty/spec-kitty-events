package org.speckitty.commandcli.adapter.in.http.edge;

/** The port the web server is bound to, or -1 before it has started. */
@FunctionalInterface
public interface OwnPortSource {
  int port();
}
