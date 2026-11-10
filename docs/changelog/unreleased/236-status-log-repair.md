---
section: Added
---

- An explicit status-log repair for one Mission, `POST /api/v1/missions/{missionId}/status-log-repairs`
  (#222; #236). It is operator-invoked and never part of a write. It takes the write path's turn and
  Mission status lock under the same budget, and it answers `none` (a healthy, missing or empty log is
  not touched), `completed` (one foldable row gets its missing LF) or `quarantined` (any other tail is
  moved to `status.events.jsonl.torn` beside the log, and the log is cut back to its last LF). The
  answer reports `readableAfterRepair`. Every write still refuses a torn tail. The contract is
  `contracts/mission-write/paths/missions_missionId_status-log-repairs.yaml`, and the operator steps
  are in `docs/guides/service-cutover-runbook.md` (section 10).
