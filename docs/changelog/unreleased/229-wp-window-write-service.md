---
section: Added
---

- Service line (`service-v0.1.0-rc.3`): the write service relays `policy_metadata.wp_window`, the
  window-opening record of core's `status-event-wp-window` contract (`schema_version` 1; core
  #7403, #7490), and the `mission-write` contract types it (#229). The CLI stamps the key from the
  same lane-head reading as `lane_head`; the service appends it as the same JSON value (subject only
  to the row-wide sanitizing and refusals every field obeys) and never refuses, delays or retries a
  unit because of its value, even a malformed one, which readers read as not recorded. `EventRow.policy_metadata.wp_window` refers to
  the closed `_shared` schemas `WpWindow`, `WpWindowGap`, `WpWindowGapReason` and `WpWindowGapCause`;
  the rest of `policy_metadata` stays untyped. The service refuses no request it accepted before;
  the contract type documents the shape and is not to be enforced on requests. Eight
  `oracle: protocol` parity cases with their appended bytes (the corpus now has 266 cases) and
  contract tests pin it. The package's event schemas are unchanged. This entry moves to
  the service-line fragment folder when #196 lands.
