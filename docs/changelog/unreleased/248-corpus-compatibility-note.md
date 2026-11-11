---
section: Compatibility
---

- The status-transition parity corpus now has 258 cases, and a unit may carry the optional
  `implementationEvidencePresent` key. The packaged `case.schema.json` is closed on the unit
  (`additionalProperties: false`), so a consumer validating against its own copy of the schema
  from an earlier release rejects the five cases that use the key: refresh that copy. `unreachable.json`
  no longer carries a `partial` entry for `implementation_evidence_required`; its `partial` object may
  be empty or absent (#248).
