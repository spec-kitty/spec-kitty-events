package org.speckitty.missionstatus.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.speckitty.missionstatus.config.MissionStatusApplicationJson;
import org.speckitty.missionstatus.testsupport.FilesystemFixtureFactory;
import org.speckitty.missionstatus.testsupport.FixtureSpec;
import org.speckitty.missionstatus.testsupport.LeakPatternsFile;
import org.speckitty.missionstatus.testsupport.PortFixtures;
import org.speckitty.missionstatus.testsupport.Rows;
import org.speckitty.missionstatus.testsupport.http.SpyQuery;

/**
 * Shutdown against a stream that is still opening. {@code endStreams} runs on {@code ContextClosedEvent}; a stream whose subscription is
 * not yet registered when it runs was missed, so its pump never ended and the container's graceful shutdown waited out its whole grace
 * period (StreamLifecycleTest measured 32 s on a loaded release runner, service-release run 41205579655). This test takes the losing
 * interleaving deterministically: the context closes first, then the stream opens.
 */
class EventsControllerShutdownTest {

  private static final Duration BOUND = Duration.ofSeconds(2);

  @Test
  void aStreamThatOpensAfterTheContextStartedClosingIsEndedToo() throws Exception {
    FixtureSpec spec =
        FixtureSpec.of(
            PortFixtures.mission(
                "alpha", Rows.ALPHA_ID, PortFixtures.CREATED, Rows.status(Rows.id(1), "2026-09-01T10:00:00Z", "WP01", "planned", "claimed")));
    SpyQuery spy = new SpyQuery(FilesystemFixtureFactory.fromSpec(spec).query());
    // A heartbeat far beyond the bound: only the shutdown can end this stream within it.
    EventsController controller =
        new EventsController(spy, MissionStatusApplicationJson.mapper(), LeakPatternsFile.load(), diagnostic -> {}, Duration.ofMinutes(10), 128);

    controller.endStreams();
    controller.events(Rows.ALPHA_ID, null, null, new MockHttpServletRequest("GET", "/api/v1/events"), new MockHttpServletResponse());

    long deadline = System.nanoTime() + BOUND.toNanos();
    while (spy.openSubscriptions() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(spy.openSubscriptions()).as("a stream opened while the context closes is ended, not left for the grace period").isZero();
  }
}
