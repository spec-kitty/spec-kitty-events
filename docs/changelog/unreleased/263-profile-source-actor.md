---
section: Added
---

- `diary.decode_actor` accepts an optional fifth key `profile_source` on the structured
  status actor: `{role, profile, tool, model}` or those four plus `profile_source` ∈
  {`invocation`, `flag`, `agent_string`, `wp_frontmatter`, `null`}, `null` exactly when
  `profile` is `null` (core's rule). Unknown keys and sources still fail closed. The producer
  contract's `actorbinding` (`lane.transitioned.v1`, `wp.innerstate.changed.v1`) and the
  registry's lifted `actor` schema gain the same optional member, so the Python and Java
  CloudEvents validators accept it too. New valid and invalid producer-row fixtures, a
  status-diary replay golden, eleven upcast corpus cases and the packaged decoder/schema parity
  gate `conformance/test_status_actor.py`. Unblocks core spec-kitty/spec-kitty#6721 (#263).
