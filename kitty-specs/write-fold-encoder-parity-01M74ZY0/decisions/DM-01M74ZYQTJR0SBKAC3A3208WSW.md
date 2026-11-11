# Decision Moment `01M74ZYQTJR0SBKAC3A3208WSW`

- **Mission:** `write-fold-encoder-parity-01M74ZY0`
- **Origin flow:** `specify`
- **Slot key:** `specify.fold.null-event-id`
- **Input key:** `null_event_id_policy`
- **Status:** `resolved`
- **Created:** `2026-11-11T09:45:18.930412+00:00`
- **Resolved:** `2026-11-11T11:20:25.836748+00:00`
- **Opened by:** `cli`
- **Other answer:** `true`

## Question

How should the write fold treat a transition with a null event_id (#231 4.16)?

## Options

- Mirror core: a null-id setter is no setter
- Refuse null transition ids when reading the log
- Other

## Final answer

Model an id-less transition explicitly through its own constructor/type (not a null or a 'None' sentinel string, not basic CRUD checks); its semantics mirror core: an id-less setter is no setter.

## Rationale

_(none)_

## Change log

- `2026-11-11T09:45:18.930412+00:00` - opened
- `2026-11-11T11:20:25.836748+00:00` - resolved (final_answer="Model an id-less transition explicitly through its own constructor/type (not a null or a 'None' sentinel string, not basic CRUD checks); its semantics mirror core: an id-less setter is no setter.")
