package org.speckitty.commandcli.adapter.in.http.edge;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * The port the web server is actually bound to, taken from the bound server (the service always configures port 0, an ephemeral port).
 * Until the server has started the port is -1 and the guard accepts nothing. Copied from the read service's {@code OwnPortHolder}.
 */
@Component
class OwnPortHolder implements OwnPortSource, ApplicationListener<WebServerInitializedEvent> {

  private volatile int port = -1;

  @Override
  public int port() {
    return port;
  }

  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    port = event.getWebServer().getPort();
  }
}
