---
section: Changed
---

- `spec_kitty_events.status_log` now sanitises tuples recursively, as core's sanitiser does, so a PII key inside a tuple-held object is dropped (#237; #231 item 4.1).
- `encode_status_row` and `sanitize_status_row` refuse unencodable input with typed errors: new `IntegerTooLarge` (an integer past the interpreter's int-to-str digit limit, no longer misreported as `NonFiniteNumber`), `NonStringKey` and `UnsupportedValue`, all `StatusRowRefused` subclasses. `NonFiniteNumber` is raised only for non-finite floats. Core would leak a bare `ValueError`/`TypeError` here; none of these inputs is reachable from `json.loads` (#237; #231 item 4.2).
- Session timestamps are parsed with the CPython 3.11 `fromisoformat` port on every interpreter, instead of the interpreter's own `fromisoformat` on 3.11+. Output on 3.11+ is now core-exact for malformed or edge-case session timestamps (for example `+00:00:00.000001`), and bytes no longer differ between 3.10, 3.11 and 3.13 (#237; #231 item 4.4).
