# Research: Ledger-write contract seam

## R-1: Byte shape of a ledger line
- **Decision:** `json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False)` followed by `\n`, encoded as ASCII.
- **Rationale:** For every JSON-native record this is byte-identical to core's fallback (`spec-kitty` `src/glossary/events.py:174-176`). A ledger that mixes canonical and fallback lines then has one byte shape. Using `ensure_ascii` removes the encoding and BOM questions.
- **Alternatives:** Compact separators with UTF-8 (`ensure_ascii=False`) give smaller lines but differ from core's existing ledgers. RFC 8785 JCS is more portable, but it renders floats differently from core and is not in the stdlib.

## R-2: Float rendering across bindings
- **Decision:** Floats use the shortest round-trip representation, as Python `repr` writes them, and fixtures pin examples (`0.5`, `0.95`, `1e-05`).
- **Rationale:** This is what core already writes. A Java binding must emulate it, for example through Ryu shortest representation plus Python exponent formatting, and the fixtures make any divergence visible.

## R-3: Atomicity
- **Decision:** A single `os.write` on an `O_APPEND` file descriptor, and a refusal on a torn tail.
- **Rationale:** POSIX `O_APPEND` positions each write atomically at end-of-file on a local filesystem. A single write call avoids interleaving at buffer boundaries, which core's buffered text-mode fallback can suffer.
- **Caveat:** NFS gives no guarantee. A torn tail left by core's fallback blocks the canonical path; the core follow-up is to stop falling back on `LedgerTornTailError`.

## R-4: Flat dicts and extra keys
- **Finding:** The glossary payload models do not set `extra="forbid"` (`glossary.py:46,64,79`), and their schemas allow additional properties. Core's flat dicts (payload fields plus `event_type`, `event_id` and `timestamp`) therefore validate.
- **Decision:** Write the input dict as given, with no projection, and add no refusal for unknown keys. Strictness stays owned by each payload model and schema, so no per-ledger rule is invented here.

## R-5: Deprecating `LOCAL_ONLY_EVENT_TYPES`
- **Decision:** Deprecate in the docs only, with no runtime warning.
- **Rationale:** Core imports the name unguarded (`lifecycle_events.py:376`), so a module `__getattr__` warning would raise under `-W error`.
