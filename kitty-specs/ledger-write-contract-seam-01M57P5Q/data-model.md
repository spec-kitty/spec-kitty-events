# Data Model: Ledger-write contract seam

| Entity | Shape | Invariants |
|---|---|---|
| Ledger | Local file, sequence of lines | Every line ends with `\n`. The file is empty or its last byte is `\n`. Only appends. |
| Record | JSON object | Has a string `event_type` that is in the conformance model map. Every value is JSON-native, with finite floats. It validates under `validate_event(..., strict=True)`. |
| Input kind `event` | `Event` instance | Record = `model_dump(mode="json")`. Envelope and inner payload are both validated. |
| Input kind `dict` | Mapping with `event_type` | Record = the dict as given. An envelope-shaped dict is validated like `event`. |
| Input kind `model` | Registered payload-model instance | Record = `{"event_type": T, **model_dump(mode="json")}`. T comes from the unique class → type map. |
| `LedgerAppendError` | Exception, subclass of `SpecKittyEventsError` | Base of every ledger refusal. |
| `LedgerValidationError` | Subclass of `LedgerAppendError` and `ValueError` | Raised before any I/O. |
| `LedgerTornTailError` | Subclass of `LedgerAppendError` | Raised before writing. The file is unchanged. |
| `LOCAL_ONLY_EVENT_TYPES` | `frozenset[str]`, empty | Deprecated; removed in 11.0; never consulted by `append_event`. |
