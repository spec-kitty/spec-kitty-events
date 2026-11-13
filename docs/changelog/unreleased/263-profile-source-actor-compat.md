---
section: Compatibility
---

- A status log row whose actor carries `profile_source` is refused by every reader before
  `11.5.0`, which fails the whole reduction. Writers must not emit the fifth key until every
  reader of the log has `11.5.0` as its floor (core raises its floor in
  spec-kitty/spec-kitty#6721). See `COMPATIBILITY.md` (#263).
