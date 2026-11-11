---
section: Changed
---

- The write-core-parity workflow test now asserts that core's own virtualenv and pack sync use core's `.python-version` interpreter, and
  that the ack-bytes oracle runs in that job (#237; #231 item 4.10).
- A command-cli ArchUnit rule confines process creation (`ProcessBuilder`, `Process`, `Runtime.exec`) to `adapter.out.mission`, with planted
  violating and passing classes proving it bites (#237; #231 item 4.11).
- The core pin's `purpose` names every CI-only peer it drives: lock interop, the nightly corpus oracle and the write-core-parity oracles
  (#237; #231 item 4.13).
