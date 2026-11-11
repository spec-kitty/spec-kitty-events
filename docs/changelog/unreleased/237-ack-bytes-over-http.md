---
section: Changed
---

- Every ack case of the status-transition parity corpus now runs over HTTP and asserts the bytes
  appended to the log against a committed test resource (#237; #231 item 4.9). 89 of the 91 ack
  cases run (the two others are asserted as refused, with their reasons, in `ProtocolCorpusTest`).
  The resource, `service/command-cli/src/test/resources/conformance/ack-bytes.json`, is extracted
  from core by `scripts/conformance/extract_ack_bytes.py` (operator ruling M2-R2: no hard coupling to
  core; the tests read only the committed file). The committed file was bootstrapped in the tool's
  `--serializer encoder` mode, because the authoring session could not execute core; CI's
  `write-core-parity` job runs the tool in core mode with `--check` and proves it equals core's output.
