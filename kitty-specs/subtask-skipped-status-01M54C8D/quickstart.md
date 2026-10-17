# Quickstart: Reading a skipped subtask

After 10.6.0, a consumer reads a status diary containing a `skipped` subtask
through the shared package — no local decode copy, no crash.

## Parse a log with a skipped subtask

```python
from spec_kitty_events.diary import parse_diary, SubtaskStatus

events = parse_diary(path_to_status_events_jsonl)
# An annotation with {"subtasks": {"T001": "skipped"}} now parses:
delta = ...  # the WPInnerStateDelta annotation
assert delta.subtasks["T001"] is SubtaskStatus.SKIPPED
```

Before 10.6.0 this raised `ValueError: 'skipped' is not a valid Lane`.

## The `not_applicable` input alias

```python
from spec_kitty_events.diary import WPInnerStateDelta, SubtaskStatus

delta = WPInnerStateDelta.from_dict({"subtasks": {"T001": "not_applicable"}})
assert delta.subtasks["T001"] is SubtaskStatus.SKIPPED  # normalized on input
assert delta.to_dict()["subtasks"]["T001"] == "skipped"  # never persisted as not_applicable
```

## Check terminality through the contract

```python
from spec_kitty_events.diary import SubtaskStatus, is_terminal_subtask

is_terminal_subtask(SubtaskStatus.DONE)  # True
is_terminal_subtask(SubtaskStatus.SKIPPED)  # True
is_terminal_subtask(SubtaskStatus.IN_PROGRESS)  # False
```

Consumers (the CLI `for_review` gate, status views) call this instead of
hard-coding `{"done", "skipped"}`.

## Backward compatibility

Every value a log used before (the `Lane` values) is still a valid
`SubtaskStatus`, so existing logs and fixtures parse and reduce identically.
`Status` remains importable as an alias of `SubtaskStatus`.
