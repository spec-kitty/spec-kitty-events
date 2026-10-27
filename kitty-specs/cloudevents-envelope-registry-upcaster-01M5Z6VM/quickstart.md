# Quickstart

```python
from spec_kitty_events.cloudevents import UpcastContext, upcast_line, canonical_bytes

ctx = UpcastContext(missionid="01J0000000000000000000MSN1", missionslug="041-demo")
for line in open("status.events.jsonl", "rb"):
    result = upcast_line(line, ctx)  # permissive: never raises on content
    if result.outcome == "mapped":
        print(canonical_bytes(result.row).decode())
    else:
        print("kept as written:", [i.code for i in result.issues])
```

Strict use (ingress): `upcast(row, ctx, strict=True)` raises `UpcastError` with `.code`.

Validate: `validate_cloudevent(row, profile="read")` returns a list of issues (needs the
`conformance` extra).

Java: `make test-java` (fetches Temurin 25 if needed) runs the same corpus.
