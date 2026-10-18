# Quickstart

```python
from spec_kitty_events.persistence import append_event, LedgerAppendError
try:
    append_event({"event_type": "MissionReopened", ...}, ".kittify/events/x.events.jsonl")
except LedgerAppendError:
    ...  # fail closed: nothing was written
```

Validate a second binding: `uv run pytest --pyargs spec_kitty_events.conformance -k ledger_append`.
