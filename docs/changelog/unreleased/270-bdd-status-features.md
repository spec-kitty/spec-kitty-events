---
section: Added
---

- BDD suite D4: the `tests/scenarios/status/` area, 16 promises driven by the committed fixtures and corpus, never copying them (#270).
  - `append/`: every `status_append` fixture is appended as its pinned bytes, or refused with its pinned refusal class.
  - `replay/`: every `status_diary/replay` stream reduces to its recorded output, in its recorded, reversed and a shuffled order.
  - `transition/`: the parity corpus is well formed, names its refusals from the closed vocabularies, and reaches every reason or explains it in `unreachable.json`. `validate_transition`'s edge check admits exactly the edges the corpus accepts unforced (of those, only the review-rejection family still needs force, as documented), refuses every illegal twin unless forced, and refuses every one-request case the corpus refuses as `illegal_transition`.
  - `actor/`: the 4-key and 5-key structured status actor decode to themselves, and the `profile_source` null-ness and unknown-value fixtures are refused, by the decoder and the `actorbinding` schema alike.
  - The services' copies of the append and transition promises are `@wip` until the `jvm` step source (#283) lands. The transition one is JVM-only: there is no Python write fold.
- BDD suite: the `@jvm-only` tag and the `jvm` promise Binding, for a promise only the JVM services implement, the mirror of an area bound in Python only. The contract check binds such a scenario against the `jvm` step source alone, pytest-bdd skips it, and it never combines with `@jvm` (operator ruling on #289).
- BDD suite: the `@manual` tag, with a required non-empty `@manual-reason:<text>`, for a specified promise this repository's CI cannot run (a host, OS, peer or operator-run step it lacks). It does not gate, pytest-bdd skips it with its reason (only when both tags are present), it never combines with `@wip`, a reason never appears without it, neither tags an `Examples:` block, and its promise keeps Binding `manual` in `PROMISES.md` (#276).
