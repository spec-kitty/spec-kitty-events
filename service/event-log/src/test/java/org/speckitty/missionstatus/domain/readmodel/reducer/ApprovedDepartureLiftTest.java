package org.speckitty.missionstatus.domain.readmodel.reducer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.speckitty.missionstatus.domain.readmodel.reducer.Events.move;
import static org.speckitty.missionstatus.domain.readmodel.reducer.Events.seed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.speckitty.missionstatus.domain.model.Lane;
import org.speckitty.missionstatus.domain.model.LedgerLine;
import org.speckitty.missionstatus.domain.model.LineKind;
import org.speckitty.missionstatus.domain.model.StatusEvent;
import org.speckitty.missionstatus.domain.model.StatusSnapshot;
import org.speckitty.missionstatus.domain.model.WpState;

/**
 * The 10.5.1 approved-departure lift of {@code _order_transitions} (spec-kitty-events#101, #102, #170; spec-kitty#4941): a non-seed
 * {@code approved -> X} recorded on a clock behind the approval it followed sorts after that approval, and the rework stamped below the
 * lifted departure is carried with it. Every expectation was taken from {@code spec_kitty_events.diary.reduce} 11.3.0 on the same rows.
 */
class ApprovedDepartureLiftTest {

  private static final StatusReducer reducer = new StatusReducer(new AtThenEventId());

  private static String t(int hour) {
    return String.format("2026-09-01T%02d:00:00Z", hour);
  }

  /** Claimed, started, handed in and picked up for review at 01:00 to 04:00. */
  private static List<StatusEvent> toReview(String wp, String prefix) {
    return List.of(
        move(prefix + "C", t(1), wp, Lane.PLANNED, Lane.CLAIMED),
        move(prefix + "S", t(2), wp, Lane.CLAIMED, Lane.IN_PROGRESS),
        move(prefix + "F", t(3), wp, Lane.IN_PROGRESS, Lane.FOR_REVIEW),
        move(prefix + "V", t(4), wp, Lane.FOR_REVIEW, Lane.IN_REVIEW));
  }

  private static StatusSnapshot reduce(List<StatusEvent> prefix, StatusEvent... rest) {
    List<StatusEvent> all = new ArrayList<>(prefix);
    all.addAll(List.of(rest));
    return reduceAll(all);
  }

  private static StatusSnapshot reduceAll(List<StatusEvent> events) {
    List<LedgerLine> lines = new ArrayList<>();
    for (StatusEvent event : events) {
      lines.add(new LedgerLine(lines.size() + 1L, "0".repeat(64), 10, new LineKind.Status(event)));
    }
    return reducer.reduceLines(lines);
  }

  private static void assertState(StatusSnapshot snapshot, String wp, Lane lane, String lastEventId) {
    WpState state = snapshot.workPackages().get(wp);
    assertThat(state.lane()).as(wp + " lane").isEqualTo(lane);
    assertThat(state.lastEventId()).as(wp + " setter").isEqualTo(lastEventId);
  }

  @Test
  void aRejectionAndItsReworkOnALaggingClockAreNotDroppedBehindAClockAheadApproval() {
    // The #170 golden case: the approval says 09:00, the reviewer's rejection 05:00 and the rework 06:00. Without the lift both sort before
    // the approval, arrive while the work package is in_review, and are dropped; the approval would win.
    StatusSnapshot snapshot =
        reduce(
            toReview("WP01", ""),
            move("A", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS),
            move("R", t(6), "WP01", Lane.IN_PROGRESS, Lane.FOR_REVIEW));
    assertState(snapshot, "WP01", Lane.FOR_REVIEW, "R");
    // The carried rework sorts last; the snapshot keeps its STORED at (only the sort key is lifted).
    assertThat(snapshot.lastEventId()).isEqualTo("R");
    assertThat(snapshot.materializedAt()).isEqualTo(t(6));
  }

  @Test
  void aBareRejectionIsLiftedAfterItsApproval() {
    StatusSnapshot snapshot =
        reduce(toReview("WP01", ""), move("A", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED), move("D", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS));
    assertState(snapshot, "WP01", Lane.IN_PROGRESS, "D");
    assertThat(snapshot.materializedAt()).isEqualTo(t(5));
  }

  @Test
  void aDepartureToDoneIsLiftedToo() {
    StatusSnapshot snapshot =
        reduce(toReview("WP01", ""), move("A", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED), move("D", t(5), "WP01", Lane.APPROVED, Lane.DONE));
    assertState(snapshot, "WP01", Lane.DONE, "D");
  }

  @Test
  void anUnskewedCycleIsUnchanged() {
    StatusSnapshot snapshot =
        reduce(
            toReview("WP01", ""),
            move("A", t(5), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D", t(6), "WP01", Lane.APPROVED, Lane.IN_PROGRESS),
            move("R", t(7), "WP01", Lane.IN_PROGRESS, Lane.FOR_REVIEW));
    assertState(snapshot, "WP01", Lane.FOR_REVIEW, "R");
    assertThat(snapshot.materializedAt()).isEqualTo(t(7));
  }

  @Test
  void eachDepartureBindsTheLatestApprovalAtOrBeforeItSoOnlyTheSkewedCycleIsLifted() {
    // Cycle 1 is un-skewed (A1 05:00, D1 06:00); cycle 2's approval ran ahead (A2 20:00, D2 10:00, rework R2 11:00). D1 binds A1, so A2 is
    // left for D2, which is lifted to 20:00 and carries R2.
    StatusSnapshot snapshot =
        reduce(
            toReview("WP01", ""),
            move("A1", t(5), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D1", t(6), "WP01", Lane.APPROVED, Lane.IN_PROGRESS),
            move("F2", t(7), "WP01", Lane.IN_PROGRESS, Lane.FOR_REVIEW),
            move("V2", t(8), "WP01", Lane.FOR_REVIEW, Lane.IN_REVIEW),
            move("A2", t(20), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D2", t(10), "WP01", Lane.APPROVED, Lane.IN_PROGRESS),
            move("R2", t(11), "WP01", Lane.IN_PROGRESS, Lane.FOR_REVIEW));
    assertState(snapshot, "WP01", Lane.FOR_REVIEW, "R2");
  }

  @Test
  void aSkewedFirstCycleCarriesItsReworkAndLeavesTheSecondCyclesApprovalToItsOwnDeparture() {
    // A1 09:00 ran ahead of D1 05:00; the second review (F2 06:00, V2 07:00) is carried behind D1; D2 11:00 binds A2 10:00 un-lifted.
    StatusSnapshot snapshot =
        reduce(
            toReview("WP01", ""),
            move("A1", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D1", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS),
            move("F2", t(6), "WP01", Lane.IN_PROGRESS, Lane.FOR_REVIEW),
            move("V2", t(7), "WP01", Lane.FOR_REVIEW, Lane.IN_REVIEW),
            move("A2", t(10), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D2", t(11), "WP01", Lane.APPROVED, Lane.DONE));
    assertState(snapshot, "WP01", Lane.DONE, "D2");
    assertThat(snapshot.lastEventId()).isEqualTo("D2");
  }

  @Test
  void aSeedDepartureIsNotLiftedAndASeedApprovalBindsNothing() {
    StatusSnapshot seedDeparture =
        reduce(toReview("WP01", ""), move("A", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED), seed("D", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS, "{d}"));
    assertState(seedDeparture, "WP01", Lane.APPROVED, "A");
    StatusSnapshot seedApproval =
        reduce(toReview("WP01", ""), seed("A", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED, "{a}"), move("D", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS));
    assertState(seedApproval, "WP01", Lane.APPROVED, "A");
    assertThat(seedApproval.lastEventId()).isEqualTo("A");
  }

  @Test
  void aDepartureWithNoApprovalToFollowIsUnchanged() {
    StatusSnapshot snapshot =
        reduceAll(List.of(move("C", t(1), "WP01", Lane.PLANNED, Lane.CLAIMED), move("D", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS)));
    assertState(snapshot, "WP01", Lane.CLAIMED, "C");
    assertThat(snapshot.lastEventId()).isEqualTo("D");
  }

  @Test
  void aSeedBelowTheLiftedDepartureIsCarriedAndStillJudgedByTheArms() {
    StatusSnapshot snapshot =
        reduce(
            toReview("WP01", ""),
            move("A", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS),
            seed("Q", t(6), "WP01", Lane.IN_PROGRESS, Lane.BLOCKED, "{q}"));
    assertState(snapshot, "WP01", Lane.BLOCKED, "Q");
  }

  @Test
  void theCarryStaysInsideItsWorkPackage() {
    // WP02's approval at 06:00 is below WP01's lifted 09:00 but is not carried: WP02 has no lifted departure.
    List<StatusEvent> prefix = new ArrayList<>(toReview("WP01", ""));
    prefix.addAll(toReview("WP02", "b"));
    StatusSnapshot snapshot =
        reduce(
            prefix,
            move("A", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS),
            move("bA", t(6), "WP02", Lane.IN_REVIEW, Lane.APPROVED));
    assertState(snapshot, "WP01", Lane.IN_PROGRESS, "D");
    assertState(snapshot, "WP02", Lane.APPROVED, "bA");
    assertThat(snapshot.lastEventId()).isEqualTo("D");
  }

  @Test
  void theLiftDoesNotDependOnTheFileOrder() {
    List<StatusEvent> rows = new ArrayList<>(toReview("WP01", ""));
    rows.addAll(
        List.of(
            move("A", t(9), "WP01", Lane.IN_REVIEW, Lane.APPROVED),
            move("D", t(5), "WP01", Lane.APPROVED, Lane.IN_PROGRESS),
            move("R", t(6), "WP01", Lane.IN_PROGRESS, Lane.FOR_REVIEW)));
    StatusSnapshot forward = reduceAll(rows);
    Collections.reverse(rows);
    StatusSnapshot reversed = reduceAll(rows);
    assertThat(reversed.workPackages()).isEqualTo(forward.workPackages());
    assertThat(reversed.lastEventId()).isEqualTo(forward.lastEventId());
  }
}
