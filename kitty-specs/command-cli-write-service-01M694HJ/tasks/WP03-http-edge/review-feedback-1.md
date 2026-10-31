# WP03 review, cycle 1: changes requested

Verified green: `:command-cli:check` exit 0 (edge: BindGuardTest 10, ConfigPinTest 1, EdgeOrderTest 35, HostOriginTableTest 154; whole module 11 suites, 0 failures, 0 errors, 0 skipped). Main code reads well: guard is a faithful copy (raw Host, Forwarded/X-Forwarded-Host, Origin, Sec-Fetch-Site, ERROR dispatch all as in the read service), order 403/401/400/413 holds and is pinned by the 15-case mask matrix, bearer is one-header/case-sensitive/constant-time, no refusal echoes a value. The blocking point is test coverage the WP required and the review brief asks to be non-vacuous.

## Blocking

1. **Error-dispatch coverage is vacuous (WP03 T014 "Keep the error-dispatch coverage"; DoD "copied guard tests are green"; Risks "Error dispatches must pass the guard and the token").**
   `HostOriginTableTest.aContainerRefusedMethodWithAForeignHostIsRefusedByTheGuardOnTheErrorDispatch` (HostOriginTableTest.java, last @Test) sends TRACE with a foreign Host. The REQUEST dispatch is refused by the guard with 403 before the container ever sends an error, so the ERROR dispatch is never reached. Removing `shouldNotFilterErrorDispatch() { return false; }` from `HostOriginFilter.java` / `BearerTokenFilter.java`, or `DispatcherType.ERROR` from `EdgeFilters.register`, leaves every test green. The read service pinned this with `HostOriginFilterTest.anErrorDispatchIsGuardedAndARequestThatAlreadyPassedPassesItsErrorRedispatchOnce` and `RegistrationTest` (order, `/*`, `determineDispatcherTypes() == {REQUEST, ERROR}`, nothing precedes the guard). Port both, extended to the token filter (ERROR registered, order GUARD+1) and to the protocol and size filters (REQUEST only, orders +2, +3).

2. **Copied test set incomplete (WP03 T014 lists HostOriginFilterTest, DirectErrorPathTest, RequestEchoTest, MalformedQueryTest, RegistrationTest).** Only the two table tests were ported. Missing, each of which guards a rule of the copied code:
   - `HostOriginFilterTest` (unit level: absent Host, port not yet known refuses everything, second foreign Origin value, body has one trailing newline). Port-unknown (`port < 0`) and the multi-Origin loop are currently untested.
   - `DirectErrorPathTest`: `GET /error` (with the token) is a 404 Problem, not a 500; a container-routed error keeps its status (TRACE with guard and token -> 405). `ProblemErrorController` has no test at all; its null-code and unresolvable-status branches are unexercised.
   - `RequestEchoTest`: the log-line part (no path or address echoed in logs) is not ported; the body part is partly covered by `doesNotContain` checks.
   - `MalformedQueryTest` may be dropped as stream-specific, but a malformed percent-encoding on a write path (e.g. `/api/v1/health?x=%zz` with guard and token) should still be a Problem, not a 500; add one row.

3. **Size cap is under-tested where the requirement is "handed on intact".**
   - No test that a body of exactly `MAX_BYTES` is accepted and one of `MAX_BYTES + 1` is refused (both declared-length and chunked). `EdgeOrderTest` only sends over-cap bodies, so changing `>` to `>=` in `BodySizeFilter` (lines `declared > MAX_BYTES`, `body.length > MAX_BYTES`) survives.
   - No test that the buffered body reaches the next handler byte-for-byte (`BufferedBody.getInputStream`/`getReader`/`getContentLength*`). A unit test with a recording servlet (MockHttpServletRequest, or a tiny controller on the rig) that echoes the byte count and a digest of the body would pin it, for a declared-length and a chunked body. The declared-length branch is also not independently pinned (the read branch alone yields the same 413); a declared 1 MiB + 1 with a short real body would pin it.

## Minor (no rework needed to approve once the above is fixed)

- `BearerTokenFilter.java` `authorized`: the `Authorization: Bearer ` (empty token) test case is received by Tomcat as `Bearer` (trailing OWS trimmed), so it exercises the scheme check, not the empty-token path. Fine, but the name is misleading.
- `RefusalBody.write` leaves `WWW-Authenticate` to the caller; fine, just keep it covered (it is, in EdgeOrderTest).
