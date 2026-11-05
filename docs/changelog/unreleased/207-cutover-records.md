---
section: Changed
---

- The service cutover records now match what happened (#207). `docs/guides/service-cutover-runbook.md`
  §7 records that the core issues were re-filed here with the operator's approval, not transferred,
  with the core-to-events issue map, and `service/core-pin.json` names only the peers the pinned core
  SHA actually serves (lock interop and the nightly corpus oracle); no golden regeneration uses it.
