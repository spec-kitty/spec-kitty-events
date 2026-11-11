---
section: Fixed
---

- Mission Status Read API: the Java `StatusReducer` now ports the 10.5.1 approved-departure lift of `diary._order_transitions` (#170, which took over spec-kitty/spec-kitty#6268). A reviewer's rejection, or an `approved -> done`, recorded on a clock behind the approval it followed was sorted before that approval and dropped as causally stale, so the read service served the approval's lane. The departure is now bound to the latest approval at or before it, else lifted to sort after the earliest later one, and the rework stamped below it is carried behind it, as `spec_kitty_events.diary.reduce_parsed` does. Only the sort key moves: stored `at` values, the precedence arms and the projections are unchanged, and a log where no clock ran ahead reduces as before. `StatusReducer.PORTED_EVENTS_VERSION` stays `11.3.0` (operator ruling: `diary.py` is unchanged since that release).
