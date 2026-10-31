---
affected_files: []
cycle_number: 1
mission_slug: command-cli-write-service-01M694HJ
reproduction_command:
reviewed_at: '2026-10-31T15:18:42Z'
reviewer_agent: claude:sonnet:reviewer-renata
wp_id: WP04
---

# WP04 review 1 (claude:sonnet:reviewer-renata): rejected for one blocking gap

The code reads correct against the contract and the spec, and `:command-cli:check` is green
(369 tests in `test`, 132 in `nativeTagJvmTest`, 0 failures, JaCoCo domain and application gates pass).
The blocking point is that the drain half of the WP is never exercised over HTTP.

## [BLOCKING] B1 No acceptance test for shutdown, draining, or the in-flight filter

FR-011, FR-009 ("While draining it returns `draining`"), T021 and the Definition of Done ("Every
operation answers per the contract") are WP04 scope. The `ServiceController.shutdown`
(`adapter/in/http/operations/ServiceController.java:44`), the draining refusal in
`WriteController.write` (`WriteController.java:53`) and `ActivityFilter` (`ActivityFilter.java:24`,
order `OperationsWiring.java:17`) have no test that reaches them:

- `grep -rn -i "shutdown\|SERVICE_DRAINING" src/test` finds only edge-order tests that send `/shutdown`
  to be refused (401/403/400), and domain enum tests. `OperationsTest` never posts a valid `/shutdown`.
- `ServiceLifecycleTest` covers the state machine only. Nothing proves the wiring: 202, `ProcessShutdown`
  reached once (`EdgeRig.exits`), the endpoint file removed, health `draining`, a write answered 503
  `SERVICE_DRAINING` with no `Retry-After` and nothing written, handshake and health still answering,
  and a second `/shutdown` answered 202 without a second stop.
- The ground rule "ATDD, red first" asks for the acceptance test of each subtask; T021 has none.
- WP05 test (c) is a process-level 202 and exit check. It does not own the 503 precedence, the
  idempotence, or the filter order, and a regression in WP04 wiring would not be caught here.

Required (tests only; no production change is expected, and a failing test is a finding to fix):

1. `POST /api/v1/shutdown` with token and protocol header: 202, empty body; then within a bounded wait
   `EdgeRig.exits` equals `[0]` and the endpoint file no longer names this instance (use a rig whose
   drain can be observed; if `EdgeRig` closes the context on exit, assert before `close()`).
2. After the shutdown (or after `lifecycle.beginDrain()` on the context bean, if the exit must not run):
   health reads `draining`; handshake answers 200; each of the three valid example writes answers 503
   `SERVICE_DRAINING` that validates against `WriteRefusal`, carries no `Retry-After`, and leaves
   `server.tree()` unchanged.
3. Precedence while draining: a malformed body still answers 503 (draining before shape), and with the
   service ready a malformed body answers 400 before the gate (already covered).
4. A second `/shutdown` answers 202 and `exits` stays at one entry.
5. The in-flight filter counts after the edge checks: a request refused by the guard, token, protocol
   or size filter leaves `lifecycle.inFlight()` at 0 and does not move `idleFor()`/last activity; an
   accepted request is counted while it runs and released after. A request in flight (for example a
   slow body) delays the stop until it ends or the 5 s budget runs out.

## Minors (not blocking; open issues or fold into the fix)

- m1 `WriteBodies.java:125`: a row with `"mission_id": null` is accepted as "absent". The contract says
  "a row that names a Mission"; null names none, so this is defensible. Add a one-line test that fixes
  the choice either way.
- m2 `IdleWatch.tick()` returns true after `beginDrain()` but nothing calls `finishDrain`; the idle
  ticker that does is WP05 (T026). Make sure WP05 calls `finishDrain(DRAIN_BUDGET, 0)` on a true tick.
- m3 `ServeRuntime` keeps a static `volatile current`; tests install their own, so parallel test classes
  that start rigs could race. Surefire/Gradle runs them serially today. A comment would do.
