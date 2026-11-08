package org.speckitty.commandcli.application.write;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.commandcli.domain.authority.Authority;
import org.speckitty.commandcli.domain.authority.Judgement;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.authority.Operation;
import org.speckitty.commandcli.domain.authority.Refusal;
import org.speckitty.commandcli.domain.authority.UnitInput;
import org.speckitty.commandcli.domain.authority.UnreducibleLog;
import org.speckitty.commandcli.domain.authority.WriteFold;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.speckitty.commandcli.domain.service.RefusalReason;

/** The complete FR-026 write check order behind Mission, log and codec ports. */
public final class WriteService {
  private static final String REF_COMMIT_DETAIL = "coordination_ref_commit is unavailable until migration step 4.";
  private static final String PERSISTENCE_DETAIL = "The status event log could not be verified or persisted; nothing was acknowledged.";

  private final MissionCatalog missions;
  private final StatusLog statusLog;
  private final RowCodec codec;
  private final WriteTurn turn;
  private final Authority authority;
  private final Duration budget;
  private final LongSupplier nanoClock;

  public WriteService(MissionCatalog missions, StatusLog statusLog, RowCodec codec, WriteTurn turn, Authority authority,
      Duration budget, LongSupplier nanoClock) {
    this.missions = missions;
    this.statusLog = statusLog;
    this.codec = codec;
    this.turn = turn;
    this.authority = authority;
    this.budget = budget;
    this.nanoClock = nanoClock;
  }

  public Duration budget() {
    return budget;
  }

  public WriteOutcome write(WriteRequest request) {
    WriteOutcome refusal = validateRows(request);
    if (refusal != null) return refusal;
    if ("coordination_ref_commit".equals(request.context().writeSurface())) {
      return refused(RefusalCode.WRITE_LOCATION_UNVERIFIED, REF_COMMIT_DETAIL, null);
    }
    MissionCatalog.FindResult found = missions.find(request.missionId());
    if (found instanceof MissionCatalog.RootUnverifiable) {
      return refused(RefusalCode.WRITE_LOCATION_UNVERIFIED, "The repository root checkout cannot be verified.", null);
    }
    if (!(found instanceof MissionCatalog.Found success)) {
      return refused(RefusalCode.MISSION_NOT_FOUND, "The Mission was not found.", null);
    }
    MissionRef mission = success.mission();
    if (!missions.locationMatches(mission, request.context().checkout(), request.context().missionDirectory(),
        request.context().destinationRef())) {
      return refused(RefusalCode.WRITE_LOCATION_UNVERIFIED, "The write location could not be verified.", null);
    }
    refusal = checkWorkPackages(request, mission);
    if (refusal != null) return refusal;
    refusal = checkDuplicateIds(request);
    if (refusal != null) return refusal;
    return lockedWrite(request, mission);
  }

  private WriteOutcome validateRows(WriteRequest request) {
    for (int index = 0; index < request.units().size(); index++) {
      for (WriteRequest.Row row : request.units().get(index).rows()) {
        String detail = codec.refusal(cast(row.value()));
        if (detail != null) return refused(RefusalCode.REQUEST_MALFORMED, detail, batchIndex(request, index));
      }
    }
    return null;
  }

  private WriteOutcome checkWorkPackages(WriteRequest request, MissionRef mission) {
    Set<String> checked = new HashSet<>();
    for (int index = 0; index < request.units().size(); index++) {
      String wpId = request.units().get(index).wpId();
      if (checked.add(wpId) && !missions.wpExists(mission, wpId)) {
        return refused(RefusalCode.WP_NOT_FOUND, "The work package was not found.", batchIndex(request, index));
      }
    }
    return null;
  }

  private WriteOutcome checkDuplicateIds(WriteRequest request) {
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < request.units().size(); index++) {
      for (WriteRequest.Row row : request.units().get(index).rows()) {
        if (!ids.add(row.eventId())) {
          return refused(RefusalCode.BOOKKEEPING_DOUBLE_EVENT_ID,
              "An event_id occurs more than once in the request.", batchIndex(request, index));
        }
      }
    }
    return null;
  }

  private WriteOutcome lockedWrite(WriteRequest request, MissionRef mission) {
    long deadline = nanoClock.getAsLong() + budget.toNanos();
    var acquired = turn.tryAcquire(deadline - nanoClock.getAsLong());
    if (acquired.isEmpty()) return refused(RefusalCode.SERVICE_BUSY, "Another write is still in progress.", null);
    try (WriteTurn.Turn ignored = acquired.orElseThrow()) {
      StatusLog.LockResult<WriteOutcome> result = statusLog.withLock(mission, deadline,
          session -> insideLock(request, mission, session));
      return switch (result) {
        case StatusLog.LockResult.Completed<WriteOutcome> completed -> completed.value();
        case StatusLog.LockResult.LockHeld<WriteOutcome> held -> refused(RefusalCode.STATUS_LOCK_HELD,
            "The Mission status lock is still held.", null);
        case StatusLog.LockResult.PersistenceFailed<WriteOutcome> failed -> refused(
            RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, PERSISTENCE_DETAIL, null);
      };
    }
  }

  private WriteOutcome insideLock(WriteRequest request, MissionRef mission, StatusLog.Session session) {
    RowCodec.DecodedLog log = codec.decode(session.currentBytes());
    MissionFacts facts;
    try {
      WriteFold.fold(log.values());
      facts = missions.facts(mission);
    } catch (UnreducibleLog exception) {
      return refused(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, PERSISTENCE_DETAIL, null);
    }
    ReplayClassifier.Result replay = ReplayClassifier.classify(request, log, codec);
    if (replay instanceof ReplayClassifier.Conflict conflict) {
      return refused(RefusalCode.EVENT_ID_CONFLICT, "The request's event ids conflict with the status event log.",
          conflict.unitIndex());
    }
    List<ReplayClassifier.Unit> units = ((ReplayClassifier.Classified) replay).units();
    List<ReplayClassifier.Unit> newUnits = units.stream().filter(unit -> !unit.replayed()).toList();
    Judgement judgement = authority.judge(log.values(), newUnits.stream().map(unit -> input(unit.request())).toList(),
        request.kind() == WriteRequest.Kind.BATCH, facts);
    if (judgement instanceof Judgement.Refused refused) return authorityRefusal(request, newUnits, refused.refusal());
    appendNewUnits(session, newUnits);
    return acknowledged(request, units);
  }

  private WriteOutcome authorityRefusal(WriteRequest request, List<ReplayClassifier.Unit> newUnits, Refusal refusal) {
    Integer index = null;
    if (request.kind() == WriteRequest.Kind.BATCH && refusal.unitIndex() != null) {
      index = newUnits.get(refusal.unitIndex()).index();
    }
    return new WriteOutcome.Refused(refusal.code(), refusal.reason(), refusal.detail(), index);
  }

  private static void appendNewUnits(StatusLog.Session session, List<ReplayClassifier.Unit> newUnits) {
    if (newUnits.isEmpty()) return;
    int size = newUnits.stream().flatMap(unit -> unit.encodedRows().stream()).mapToInt(row -> row.length).sum();
    byte[] append = new byte[size];
    int offset = 0;
    for (ReplayClassifier.Unit unit : newUnits) {
      for (byte[] row : unit.encodedRows()) {
        System.arraycopy(row, 0, append, offset, row.length);
        offset += row.length;
      }
    }
    session.appendAndSync(append);
  }

  private static WriteOutcome acknowledged(WriteRequest request, List<ReplayClassifier.Unit> units) {
    List<WriteOutcome.UnitAck> acknowledgements = new ArrayList<>();
    for (ReplayClassifier.Unit unit : units) {
      acknowledgements.add(new WriteOutcome.UnitAck(unit.eventIds(), unit.replayed() ? 0 : unit.encodedRows().size(),
          unit.replayed()));
    }
    boolean batch = request.kind() == WriteRequest.Kind.BATCH;
    int fsyncs = units.stream().allMatch(ReplayClassifier.Unit::replayed) ? 0 : 1;
    return new WriteOutcome.Acked(acknowledgements, batch, fsyncs);
  }

  private static UnitInput input(WriteRequest.UnitRequest unit) {
    return new UnitInput(unit.wpId(), Operation.fromWire(unit.operation() == null ? "annotation" : unit.operation().wireValue()),
        unit.rows().stream().map(row -> cast(row.value())).toList());
  }

  private static WriteOutcome refused(RefusalCode code, String detail, Integer index) {
    return new WriteOutcome.Refused(code, null, detail, index);
  }

  private static Integer batchIndex(WriteRequest request, int index) {
    return request.kind() == WriteRequest.Kind.BATCH ? index : null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object value) {
    return (Map<String, Object>) value;
  }
}
