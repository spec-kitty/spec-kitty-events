package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.speckitty.commandcli.application.diagnostics.FailureRecord;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.speckitty.commandcli.application.repair.RepairOutcome;
import org.speckitty.commandcli.application.repair.StatusLogRepairService;
import org.speckitty.commandcli.application.write.WriteOutcome;
import org.speckitty.commandcli.application.write.WriteRequest;
import org.speckitty.commandcli.application.write.WriteService;
import org.speckitty.commandcli.application.write.WriteTurn;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.commandcli.domain.authority.Authority;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/** FR-016/NFR-003: every last-resort catch-all leaves one coded, content-free JUL warning, and only its owning layer logs it. */
class CatchAllLoggingTest {
  private static final String MISSION = "01J6XW9KQT7M0YB3N4R5CQZ2EX";
  private static final String EVENT_ID = "01J6XW9KQT7M0YB3N4R5CQZ2EY";
  private static final String CODE = RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED.name();
  private static final String MARKER_PATH = "/tmp/MARKER-PATH";
  private static final String MARKER_TOKEN = "MARKERTOKEN0123456789abcdef";
  private static final String MARKER_ROW = "MARKER-ROW";
  private static final List<String> MARKERS = List.of(MARKER_PATH, MARKER_TOKEN, MARKER_ROW);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  // JUL keeps loggers weakly: these test fields keep the exact loggers attached to System.Logger alive for every capture.
  private final Logger writeServiceLogger = Logger.getLogger(WriteService.class.getName());
  private final Logger writeControllerLogger = Logger.getLogger(WriteController.class.getName());
  private final Logger repairServiceLogger = Logger.getLogger(StatusLogRepairService.class.getName());
  private final Logger repairControllerLogger = Logger.getLogger(StatusLogRepairController.class.getName());

  static List<Arguments> writeOperations() {
    return List.of(
        Arguments.of(WriteRequest.Kind.UNIT, "recordWorkPackageTransition"),
        Arguments.of(WriteRequest.Kind.BATCH, "recordWorkPackageTransitionBatch"),
        Arguments.of(WriteRequest.Kind.ANNOTATION, "recordInnerStateAnnotation"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("writeOperations")
  void writeServiceLogsExactlyOneContentFreeWarning(WriteRequest.Kind kind, String operation) {
    MarkerException failure = new MarkerException(markerText());
    try (LogCapture capture = new LogCapture(writeServiceLogger)) {
      WriteOutcome outcome = failingWriteService(failure).write(writeRequest(kind));

      assertThat(outcome).isInstanceOf(WriteOutcome.Refused.class);
      assertThat(((WriteOutcome.Refused) outcome).code()).isEqualTo(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED);
      assertSingleWarning(capture, operation, failure);
    }
  }

  @Test
  void repairServiceLogsExactlyOneContentFreeWarning() {
    MarkerException failure = new MarkerException(markerText());
    MissionCatalog missions = mock(MissionCatalog.class);
    when(missions.find(MISSION)).thenThrow(failure);
    StatusLogRepairService repairs = new StatusLogRepairService(missions, mock(StatusLog.class), mock(RowCodec.class), new WriteTurn(),
        Duration.ofSeconds(1), System::nanoTime);
    try (LogCapture capture = new LogCapture(repairServiceLogger)) {
      RepairOutcome outcome = repairs.repair(MISSION, markerContext());

      assertThat(outcome).isInstanceOf(RepairOutcome.Refused.class);
      assertThat(((RepairOutcome.Refused) outcome).code()).isEqualTo(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED);
      assertSingleWarning(capture, "repairStatusLog", failure);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("writeOperations")
  void writeControllerLogsExactlyOneContentFreeWarning(WriteRequest.Kind kind, String operation) {
    MarkerException failure = new MarkerException(markerText());
    WriteService writes = mock(WriteService.class);
    when(writes.write(any())).thenThrow(failure);
    WriteController controller = new WriteController(lifecycle(), writes, new WriteBodies(), JSON);
    try (LogCapture capture = new LogCapture(writeControllerLogger)) {
      ResponseEntity<byte[]> response = switch (kind) {
        case UNIT -> controller.recordWorkPackageTransition(MISSION, bytes(writeBody("unit")));
        case BATCH -> controller.recordWorkPackageTransitionBatch(MISSION, bytes(writeBody("batch")));
        case ANNOTATION -> controller.recordInnerStateAnnotation(MISSION, bytes(writeBody("annotation")));
      };

      assertCoded500(response);
      assertSingleWarning(capture, operation, failure);
    }
  }

  @Test
  void repairControllerLogsExactlyOneContentFreeWarning() {
    MarkerException failure = new MarkerException(markerText());
    StatusLogRepairService repairs = mock(StatusLogRepairService.class);
    when(repairs.repair(any(), any())).thenThrow(failure);
    StatusLogRepairController controller = new StatusLogRepairController(lifecycle(), repairs, new WriteBodies(), JSON);
    try (LogCapture capture = new LogCapture(repairControllerLogger)) {
      ResponseEntity<byte[]> response = controller.repairStatusLog(MISSION, bytes(repairBody()));

      assertCoded500(response);
      assertSingleWarning(capture, "repairStatusLog", failure);
    }
  }

  @Test
  void aWriteFailureCaughtByTheServiceIsNotLoggedAgainByTheController() {
    MarkerException failure = new MarkerException(markerText());
    WriteController controller = new WriteController(lifecycle(), failingWriteService(failure), new WriteBodies(), JSON);
    try (LogCapture serviceCapture = new LogCapture(writeServiceLogger);
        LogCapture controllerCapture = new LogCapture(writeControllerLogger)) {
      ResponseEntity<byte[]> response = controller.recordWorkPackageTransition(MISSION, bytes(writeBody("unit")));

      assertCoded500(response);
      assertSingleWarning(serviceCapture, "recordWorkPackageTransition", failure);
      assertThat(controllerCapture.records).isEmpty();
    }
  }

  @Test
  void aRepairFailureCaughtByTheServiceIsNotLoggedAgainByTheController() {
    MarkerException failure = new MarkerException(markerText());
    MissionCatalog missions = mock(MissionCatalog.class);
    when(missions.find(MISSION)).thenThrow(failure);
    StatusLogRepairService repairs = new StatusLogRepairService(missions, mock(StatusLog.class), mock(RowCodec.class), new WriteTurn(),
        Duration.ofSeconds(1), System::nanoTime);
    StatusLogRepairController controller = new StatusLogRepairController(lifecycle(), repairs, new WriteBodies(), JSON);
    try (LogCapture serviceCapture = new LogCapture(repairServiceLogger);
        LogCapture controllerCapture = new LogCapture(repairControllerLogger)) {
      ResponseEntity<byte[]> response = controller.repairStatusLog(MISSION, bytes(repairBody()));

      assertCoded500(response);
      assertSingleWarning(serviceCapture, "repairStatusLog", failure);
      assertThat(controllerCapture.records).isEmpty();
    }
  }

  private static WriteService failingWriteService(RuntimeException failure) {
    MissionCatalog missions = mock(MissionCatalog.class);
    when(missions.find(MISSION)).thenThrow(failure);
    return new WriteService(missions, mock(StatusLog.class), mock(RowCodec.class), new WriteTurn(), Authority.standard(),
        Duration.ofSeconds(1), System::nanoTime);
  }

  private static WriteRequest writeRequest(WriteRequest.Kind kind) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("event_id", EVENT_ID);
    value.put("wp_id", "WP01");
    value.put("actor", markerText());
    WriteRequest.UnitRequest unit = new WriteRequest.UnitRequest("WP01",
        kind == WriteRequest.Kind.ANNOTATION ? null : WriteRequest.Operation.TRANSITION,
        List.of(new WriteRequest.Row(EVENT_ID, value)));
    return new WriteRequest(MISSION, kind, List.of(unit), markerContext());
  }

  private static WriteRequest.Context markerContext() {
    return new WriteRequest.Context(markerText(), ".", "kitty-specs/" + MARKER_ROW, "checkout_append", null);
  }

  private static String writeBody(String surface) {
    String transition = WriteRig.claimRow(EVENT_ID).replace("java-jenny", markerText());
    return switch (surface) {
      case "unit" -> WriteRig.unit(transition);
      case "batch" -> WriteRig.batch(WriteRig.unit(transition));
      case "annotation" -> WriteRig.annotation("WP01", WriteRig.annotationRow(EVENT_ID, "WP01", markerText()));
      default -> throw new IllegalArgumentException(surface);
    };
  }

  private static String repairBody() {
    return WriteRig.repairRequest(WriteRig.context().replace("java-jenny", markerText()));
  }

  private static String markerText() {
    return MARKER_PATH + " " + MARKER_TOKEN + " " + MARKER_ROW;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static ServiceLifecycle lifecycle() {
    return new ServiceLifecycle(Clock.systemUTC(), () -> { });
  }

  private static void assertCoded500(ResponseEntity<byte[]> response) {
    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("\"code\":\"" + CODE + "\"");
  }

  /** The exception class is the positive control; markers in its message and the request must all be absent. */
  private static void assertSingleWarning(LogCapture capture, String operation, Throwable failure) {
    assertThat(capture.records).hasSize(1);
    LogRecord record = capture.records.getFirst();
    assertThat(record.getLevel()).isEqualTo(Level.WARNING);
    assertThat(record.getMessage()).isEqualTo(FailureRecord.describe(operation, CODE, failure));
    assertThat(record.getMessage()).contains(failure.getClass().getName());
    assertThat(record.getParameters()).isNull();
    assertThat(record.getThrown()).isNull();
    for (String marker : MARKERS) {
      assertThat(record.getMessage()).doesNotContain(marker);
    }
  }

  private static final class MarkerException extends RuntimeException {
    MarkerException(String message) {
      super(message);
    }
  }

  /** Attaching and detaching in try-with-resources gives every test the required finally cleanup, including failed assertions. */
  private static final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final Handler handler;
    private final List<LogRecord> records = new ArrayList<>();

    LogCapture(Logger logger) {
      this.logger = logger;
      previousLevel = logger.getLevel();
      handler = new Handler() {
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
      };
      handler.setLevel(Level.ALL);
      logger.setLevel(Level.ALL);
      logger.addHandler(handler);
    }

    @Override
    public void close() {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
      handler.close();
    }
  }
}
