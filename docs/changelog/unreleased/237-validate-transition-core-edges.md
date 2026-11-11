---
section: Changed
---

- `validate_transition()` and `reduce_status_events()` now admit exactly core's unforced edge graph (`wp_state.py` at spec-kitty `512567ee`) plus `None -> planned`, in a minor release, `11.5.0` (#237; #231 item 4.6; ruling D4). Unforced edges removed: `for_review -> approved`, `for_review -> done`, `for_review -> in_progress`, `for_review -> planned`, `in_review -> for_review`, `genesis -> blocked` and `blocked -> blocked`. Producers must force those moves with a reason (and a `review_ref` where required), as core does. Force semantics and guards are unchanged. The fixtures `wp-status-changed-unforced-for-review-to-in-progress-with-review-ref-valid` and `lifecycle-wp-status-changed-summary-done-valid` are now forced events; their unforced forms are new invalid fixtures. The package docstring now lists `spec_kitty_events.status_log` (#231 item 4.3).
