package org.speckitty.commandcli.application.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class FailureRecordTest {
  private static final String OPERATION = "repairStatusLog";
  private static final String CODE = "STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED";

  @Test
  void aThreeLinkCauseChainUsesOnlyClassNamesAndIgnoresSuppressedFailures() {
    AccessDeniedException denied = new AccessDeniedException("/home/x/secret");
    UncheckedIOException io = new UncheckedIOException("/home/x/secret", denied);
    IllegalStateException failure = new IllegalStateException("/home/x/secret", io);
    failure.addSuppressed(new IllegalArgumentException("suppressed must not appear"));

    String record = FailureRecord.describe(OPERATION, CODE, failure);

    assertThat(record).isEqualTo(OPERATION + " failed with " + CODE
        + ": java.lang.IllegalStateException <- java.io.UncheckedIOException <- java.nio.file.AccessDeniedException");
    assertThat(record).doesNotContain("/home/x/secret", "suppressed", "IllegalArgumentException");
  }

  @Test
  void aCauseCycleStopsAtTheFirstRepeatedIdentity() {
    LinkedThrowable first = new LinkedThrowable();
    LinkedThrowable second = new LinkedThrowable();
    first.initCause(second);
    second.initCause(first);

    String record = FailureRecord.describe(OPERATION, CODE, first);

    assertThat(record).isEqualTo(OPERATION + " failed with " + CODE + ": "
        + LinkedThrowable.class.getName() + " <- " + LinkedThrowable.class.getName());
  }

  @Test
  void aChainLongerThanEightIsCappedWithAnEllipsis() {
    Throwable failure = new RuntimeException("leaf");
    for (int depth = 0; depth < 11; depth++) {
      failure = new RuntimeException("level " + depth, failure);
    }

    String record = FailureRecord.describe(OPERATION, CODE, failure);

    String classes = String.join(" <- ", Collections.nCopies(8, RuntimeException.class.getName()));
    assertThat(record).isEqualTo(OPERATION + " failed with " + CODE + ": " + classes + " <- …");
  }

  private static final class LinkedThrowable extends Throwable {
    private Throwable linkedCause;

    @Override
    public synchronized Throwable getCause() {
      return linkedCause;
    }

    @Override
    public synchronized Throwable initCause(Throwable cause) {
      linkedCause = cause;
      return this;
    }
  }
}
