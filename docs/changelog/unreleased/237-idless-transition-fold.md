---
section: Fixed
---

- The write service no longer treats a transition with no `event_id` as a work package's
  current-lane setter. Core's reducer finds no setter for such a row, so on a hand-written log the
  two could disagree on the lane (#237; #231 item 4.16). A non-finite log `event_id` stays a coded
  500 with nothing written, now pinned by a test (#231 item 4.17).
