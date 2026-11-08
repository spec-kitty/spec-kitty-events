---
title: WP08 core-parity divergence audit
---

> Recorded 2026-11-08 under the maintainer ruling that each divergence from core is judged by why core behaves as it does. Core issues filed: I1 #7439, I2 #7440, I3 #7441, I4 #7442, I5 #7443, I6 #7444, I7 #7445 (spec-kitty/spec-kitty). I8 is a design question left to the maintainer. YAML 1.2.2 quotes verified against yaml.org.

# WP08 divergence audit: why core behaves as it does

Mission `events-write-core-01M6TP6W`, core pinned at `512567ee` (ruamel.yaml 0.19.1, Python 3.11.15).
Maintainer ruling (2026-11-08): an intentional guard in core is matched and blocks. A hidden bug or accident in core or its stack is not copied: it gets a ruling, plus an issue when core is at fault.
Investigator: read-only audit, 2026-11-08. No repo was modified.

## Evidence produced for this audit (all in this directory)

| File | What it shows |
|---|---|
| `scan_real.py`, `scan_real.out`, `rostertypes.py`, `datefields.py` | 47,926 real `tasks/WP*.md` paths on this machine, de-duplicated by frontmatter text (probe dirs under `wp08-diff/` excluded): **4,665 unique frontmatters**, read with core's splitter and ruamel `YAML()`. |
| `repro_yaml.py` → `repro_yaml.out` | 46 YAML cases through core's real `read_frontmatter` (`FrontmatterManager.read`), next to PyYAML 6.0.3 (an independent parser in the same venv). |
| `repro_flow.py` | Flow-context `:` cases, ruamel against PyYAML. |
| `repro_bigint.py` → `repro_bigint.out` | Python's 4300-digit `int` limit, `shell_pid` coercion and date values, through `read_frontmatter` and `authored_subtask_roster`. |
| `repro_log.py` | `store.read_events` (uses `splitlines()`) against `store.read_events_raw` (iterates the file), plus the int and nesting limits. |
| `jcases/`, `jpaths.txt` | The same inputs through the **service** (`Frontmatter.read` + `FactValues`), using the cycle-7 harness `cycle7/rev7/harness/Facts2` and its jar (code at worktree HEAD `ccbdb9eb`, before the cycle-7 fixes). |

Facts about the real corpus that drive the practical-impact calls:
- 0 of 4,665 have a non-string mapping key, a complex key, a NaN, a tab, U+0085/U+2028/U+2029, a tag (one `!` hit was inside text), or a duplicate key.
- 1 fails to parse, and it is a core test fixture (`test_unparseable_wp_degrades`).
- 8 use block scalars, and none trips ruamel's block-scalar check.
- About 1,434 store `shell_pid` as a quoted string. Every one is ASCII digits or `""`.
- **28 subtask entries are maps** (`- {id: T024, title: …, status: planned}`), in 6 WPs of core's own mission `058-hybrid-prompt-and-shim-agent-surface`. Core renders them as Python dict reprs (class 1b).
- Real extra keys that `WPMetadata` forbids: `pre_review_test_scope` (core mission `pin-absent-lane-wording-7200-01M6P3HX/WP01`), plus fixture-only `wp_id`, `id`, `evidence`, `old_priority_field`. 54 WPs lack a `subtasks:` key.

### One cross-cutting fact for the practical-impact column

`FrontmatterManager.read` is core's **single** frontmatter boundary (`src/specify_cli/frontmatter.py:1-8`: "This module provides the ONLY way to read and write YAML frontmatter"). When it raises, every core path that reads that WP's frontmatter fails, not just the status guards. Divergences therefore fall into two kinds:
- **File-wide accidents**: core cannot read the file at all (ruamel scanner/constructor errors, the unhashable keys, the int limit). Core is already broken on that WP, so the service accepting it changes little in practice. The cheapest safe answer is a **simple, spec-agnostic fail-closed restriction** that covers the accident without modelling it. That does not copy the accident. It is a recorded ruling in the R19–R21 style.
- **Narrow accidents**: core reads the file but one guard's value rule is accidental (str()-rendered ids, `shell_pid` `int()` forms, the locator case split). Core otherwise works on that WP, so a divergence shows up as a real verdict difference. Prefer the spec-correct or simpler rule, refuse wherever refusing is cheap, and file the issue.

## Summary table

Verdicts: `INTENTIONAL-GUARD`, `SPEC-MANDATED`, `CORE-ACCIDENT`, `STACK-ACCIDENT` (Python or ruamel semantics core never chose), `CORE-BUG`.
**Blocks** means the service must match core before WP08 can pass. "met" means it already does.

| # | Class | Direction (svc vs core) | Verdict | Recommendation | Blocks? |
|---|---|---|---|---|---|
| 1 | Roster resolution: missing `subtasks`, extra keys, missing/invalid `work_package_id`, wrong types (R15) | was fail-open; now matched | INTENTIONAL-GUARD | Match (keep R15 mapping) | **yes** (met) |
| 1b | Non-scalar subtask entries rendered with `str()` (maps, lists, dates, floats) | either way (ids never match) | CORE-BUG (real files in mission 058) | Diverge: entries must be `str` or `int`, else roster Unresolvable (R15). core spec-kitty/spec-kitty#7442 (I4) | no |
| 2 | Frontmatter read: BOM, universal newlines, fences, empty block → `{}`, textual duplicate keys (top-level and nested) | matched | INTENTIONAL-GUARD (+ SPEC-MANDATED for duplicates) | Match | **yes** (met) |
| 3 | Task globbing: `*.md`, word-boundary pattern, README excluded, zero/many matches | matched | INTENTIONAL-GUARD | Match | **yes** (met) |
| 3b | Directories and dangling symlinks count toward ambiguity | matched | STACK-ACCIDENT (on the fail-closed side of an intentional guard) | Keep: counting names is the simpler rule. Folded into I5 | no |
| 3c | Roster locator case-insensitive, dependency locator case-sensitive | matched (copied) | CORE-ACCIDENT | Diverge: case-insensitive for both (a fail-closed superset). core spec-kitty/spec-kitty#7443 (I5) | no |
| 4 | Own `meta.json` with a UTF-8 BOM → Mission not found | matched | INTENTIONAL (documented contract of `load_meta`; inconsistent with `load_meta_strict`) | Match (404); diverging would write to a Mission core cannot see. Low-priority core spec-kitty/spec-kitty#7445 (I7) | yes (met) |
| 5 | Status log split with `str.splitlines()`; raw U+2028/U+0085/VT/FF/FS/GS/RS inside a row → unreadable | matched (copied) | CORE-ACCIDENT (2026-06 refactor; core's other reader disagrees) | Do not model `splitlines`: refuse any raw U+0085/U+2028/U+2029/VT/FF/FS/GS/RS in the log (R24), or split on universal newlines only. core spec-kitty/spec-kitty#7439 (I1) | no |
| 6 | Coordination-routed Mission (R16) | fail-closed (core writes, under a mid8 lock key) | INTENTIONAL in core; the service's refusal is a contract choice (decision 6) | Keep R16 (two writers on two lock keys is a lost-update hazard) | no |
| 7 | Explicit tags; ruamel `TaggedScalar` (R19), and the untagged `=` | fail-closed superset | Mixed: core accepting `!!int 12` is spec-correct; core refusing `!!str 5` and `=` is STACK-ACCIDENT | Keep R19. **Replace the `=`→`TaggedScalar` model and its code-point list coercion** with "a plain `=` scalar → unresolvable" | no |
| 8 | Scanner gaps: tabs, U+0085/U+2028/U+2029, directives, anchors/aliases, `...` (R20) | fail-closed superset | Mixed: tab indentation and a directive with no document are SPEC-MANDATED refusals; in-line tabs, NEL folding are STACK; U+2028, anchors, `...` are read correctly by core | Keep R20 | no |
| 9 | Duplicate keys under Python equality (`1`/`1.0`/`true`, `-0.0`/`0`, `[1]`/`[1.0]`) | was fail-open; now copied | STACK-ACCIDENT, but benign (Python cannot represent the mapping) | **Replace the Python-equality port with R22**: every mapping key must be a string scalar, judged by the YAML 1.2 core schema. No core issue | no (once R22 lands) |
| 10 | Duplicate `<<` merge keys | matched | SPEC-MANDATED (+ ruamel 0.18.15 deliberate) | Match | **yes** (met) |
| 11 | Flow-context `:` without a following space (R21) | fail-closed superset | Mixed: `{a:[]}` refusal SPEC-MANDATED; `['a':b]` refusal and `[a:]`→`'a:'` are ruamel bugs | Keep R21 | no |
| 12 | Block scalar "more indented follow up line than first" (cycle-7 finding 2) | **fail-open today** for some inputs | **STACK-ACCIDENT** (ruamel-only check, stricter than YAML 1.2.2 §8.1.1.1; PyYAML reads them) | **Do not port ruamel's rule** (withdraw cycle-7 finding 2 as worded). R23: a block scalar without an indentation indicator whose first content line is non-empty and spaces-only → unresolvable. core spec-kitty/spec-kitty#7444 (I6) | no |
| 13 | NaN keys equal via ruamel's shared `nan_value` (cycle-7 finding 1) | fail-open today | Two top-level `.nan` keys: SPEC-MANDATED duplicate (by coincidence). NaN inside complex keys: STACK | Covered by R22 (numeric keys refused). Finding 1 becomes moot | no (once R22 lands) |
| 14 | Complex keys core cannot hash (cycle-7 finding 3) | fail-open today | STACK-ACCIDENT (YAML permits any node as a key) | Covered by R22 (complex keys refused). Finding 3 becomes moot | no (once R22 lands) |
| 15 | `shell_pid` via Python `int()`: Unicode-14 digits, `_`, bool, float, whitespace | matched (copied) | STACK-ACCIDENT (the intent is "quoted digit string or `''`") | Diverge to: `null`, `''`, YAML int, or an ASCII `[+-]?[0-9]+` string after Python strip, **at most 4300 digits**. Drop the Unicode-14 table and its JDK test | no |
| 15b | **NEW:** `shell_pid` string longer than 4300 digits | **fail-open** (verified) | STACK-ACCIDENT (CPython `int_max_str_digits`) | Fixed by the 4300-digit cap in 15 | yes, until ruled (3-line fix) |
| 16 | 4300-digit ints and nesting in the status log | 500 in both; boundary differs for nesting | STACK-ACCIDENT; core leaks `ValueError`/`RecursionError` out of the `StoreError` contract (CORE-BUG, minor) | Keep the 4300 cap; replace the nesting cap of 1000 with a fixed lower bound (e.g. 256), so the service never accepts a depth core's stack rejects. core spec-kitty/spec-kitty#7439 (I1) | no |
| 16b | **NEW:** a decimal int with more than 4300 digits anywhere in the frontmatter | **fail-open** (verified: core refuses both guards, service resolves both) | STACK-ACCIDENT, file-wide | Restriction: an int scalar with more than 4300 decimal digits → unresolvable | yes, until ruled (3-line fix) |
| B-R17 | Separate git dir and submodules refused | fail-closed | core acceptance not a bug | Keep | no |
| B-R18 | WP file larger than 16 MiB refused | fail-closed | core acceptance not a bug | Keep | no |
| B-R21res | `:` block, `<<` as a value, `"\/"`, `[:x]`/`[a?]`/`[-?]`/`[::]`, nesting 49+ | fail-closed | Core is spec-correct on all except `<<` (ruamel's YAML 1.1 merge relic) | Keep as residue | no |
| B-R21res | Complex map keys differing only in order | fail-closed | **Core is wrong** (§3.2.1.3: equal mappings → duplicate; ruamel hash/eq bug) | Keep; service is spec-correct. core spec-kitty/spec-kitty#7444 (I6) | no |
| B-rev7 | Timestamp keys for the same instant | fail-closed | **Core is wrong** (YAML timestamp: omitted zone = UTC → equal → duplicate) | Keep (subsumed by R22) | no |
| B-rev7 | `=` vs `'='` inside complex keys | fail-closed | YAML 1.2: both are `str` "=" → duplicate; core reads because of ruamel's 1.1 `value` tag | Keep (subsumed by R22 and the `=` rule) | no |
| B-rev7 | Python-equal keys across merge sources or overridden by an explicit key | fail-closed | Core correct (merge semantics: overrides are not duplicates) | Keep as residue | no |
| B-res | YAML 1.1 vs 1.2 resolver: sexagesimal, `010`, `0o10`, `1_000` | **can be fail-open** | **Service's own bug** (SnakeYAML resolves with 1.1 rules); core is spec-correct (1.2) | Fix in the service: resolve plain scalars by the 1.2 core schema (§10.3.2), or refuse the 1.1-only numeric forms | open issue (service) |
| B-res | `RootCheckout` reads only the first `[core]`; quoted `bare` | n/a | Service-side config parsing; core asks git | Fix in the service; not core's | no |
| B-d9a | Non-finite numbers in a row | fail-closed | CORE-BUG (writes `NaN`/`Infinity`, non-RFC JSON) | Keep `REQUEST_MALFORMED`. core spec-kitty/spec-kitty#7441 (I3) | no |
| B-d9b | Mixed naive/aware session times | fail-closed | CORE-BUG (uncaught `TypeError`) | Keep `REQUEST_MALFORMED`. core spec-kitty/spec-kitty#7440 (I2) | no |
| B-d10 | CRLF on Windows | fail-closed on write, tolerant on read | STACK-ACCIDENT (text mode) | Keep. Folded into I3 | no |
| B-C006 | Torn tail refused | fail-closed | Core's normalization of a missing final LF is INTENTIONAL (`store.py:357`, #946); a real partial row fails core's read anyway | Keep (operator ruling 2026-10-31) | no |
| B-1 | Roster gate validates the whole `WPMetadata`; the dependency gate deliberately does not | matched | INTENTIONAL, but inconsistent by design | Match. Raise it with core as a design question (**I8**, optional) | — |

---

## Class sections

### 1. Subtask-roster resolution (R15): fail-open before, matched now

**Mechanism.**
- `src/specify_cli/core/subtask_rows.py:289-326` `authored_subtask_roster`:
  - `_locate_wp_file` (`:258-286`);
  - `FrontmatterManager().read` (`:314`);
  - `"subtasks" not in frontmatter` → `SubtaskRosterMissingError` (`:315-318`);
  - `WPMetadata.model_validate(frontmatter, strict=False)` (`:319`);
  - any other exception → `SubtaskRosterResolutionError` (`:322-325`).
- `WPMetadata` (`src/specify_cli/status/wp_metadata.py:208-313`) has `extra="forbid"` (`:215-219`), `work_package_id: str` matching `^WP\d{2,}$` (`:222`, `:351-356`), `dependencies: list[str]` (`:226`), `subtasks: list[Any]` (`:268`) and `shell_pid: int | None` (`:276`).

**Evidence of intent.**
- Docstring `subtask_rows.py:302-305`: "Only an explicitly readable empty `subtasks` list yields `[]` … Missing, ambiguous, or malformed WP metadata raises :class:`SubtaskRosterResolutionError` so transition callers fail closed."
- `_locate_wp_file` docstring `:262-264`: "collapsing them to an empty roster would make the review gate fail open".
- `wp_metadata.py:8-10`: "Uses `extra="forbid"` to reject unrecognised fields at parse time. If a new frontmatter key appears in the wild, add it to the model before it can be used."
- Tests in `tests/specify_cli/core/test_subtask_rows_snapshot.py`: `test_missing_subtasks_key_fails_closed` (`:188`), `test_missing_wp_file_fails_closed` (`:200`), `test_ambiguous_wp_files_fail_closed` (`:206`), `test_malformed_wp_file_fails_closed` (`:218`).
- Commit `dfe6b2ead4` (#2816, "runtime-state corpus cutover").

**Verdict.** INTENTIONAL-GUARD. **Recommendation:** match (done; R15 maps it to `409 implementation_evidence_required`).

**Practical impact.** Real: `pin-absent-lane-wording-7200-01M6P3HX/WP01` carries `pre_review_test_scope`, so core refuses its unforced `for_review`. The service refuses too, so the two agree. 54 legacy WPs without `subtasks:` are refused by both, as designed.

**Note (B-1).** Core made the opposite choice for the dependency guard on purpose. `status/emit.py:331-337`: "whole-model `WPMetadata` validation would refuse status writes for defects unrelated to dependencies". So a cosmetic metadata defect (an unknown key, `title: ""`, `base_commit: main`) blocks `for_review` but never a claim. That is designed on both sides, so the service matches it. Optional issue I8 asks core whether the asymmetry is wanted.

#### 1b. Non-scalar subtask entries: CORE-BUG
- `normalize_authored_subtask_roster` (`subtask_rows.py:365-374`) does `str(raw).strip()`. The docstring (`:302-303`, "coerced to `str`") means ints such as `1` → `"1"`.
- Maps become `"{'id': 'T024', 'title': '…', 'status': 'planned'}"` (reproduced on the real `058-hybrid-prompt-and-shim-agent-surface/tasks/WP06-tests-and-validation.md`). No `mark-status` id ever equals that, so core's gate can never be satisfied without `--force`.
- Lists, dates and floats render as Python reprs. Java renders `{id=T024, …}`, so both refuse in practice. Fail-open is reachable only if a snapshot contains Java's rendering, which is contrived.
- **Recommendation:** roster entries must be `str` or `int` (Python `str(int)` equals Java `BigInteger.toString`). Anything else (map, list, null, bool, float, date) → Unresolvable → R15. This is fail-closed against core's `[true]`/`[1.1]`/`[~]` (core renders `"True"`/`"1.1"`/`"None"`). It retires cycle-2 minor 7's `[1.1]` case: move that case to `oracle: protocol`.
- Ruling text **R25:** "A subtask-roster entry that is not a string or an integer makes the roster unresolvable (R15). Core coerces such entries with Python `str()`, which yields ids no subtask event can match (core issue I4 (spec-kitty/spec-kitty#7442)); the service refuses instead of rendering Python reprs."

### 2. `FrontmatterManager.read` rules: matched

**Mechanism** (`src/specify_cli/frontmatter.py`):
- `read_text(encoding="utf-8-sig")` (`:116`), in text mode with universal newlines;
- `content.startswith("---")` (`:118`);
- closing fence `line.rstrip() == "---"` (`:121-128`);
- `yaml.load`, with `None` → `{}` (`:136-138`);
- `DuplicateKeyError` → legible `FrontmatterError` (`:139-143`), any other exception → `FrontmatterError` (`:144-145`);
- not a mapping → error (`:151-152`);
- a WP with no `dependencies` → `[]` (`:155-156`).

**Evidence of intent.**
- BOM: commit `a749383c48` (#48): "UTF-8 BOM handling across all file operations".
- Fence: commit `cabbb47abc`: "preserve indented rules in WP frontmatter block scalars", with the comment at `:121-122`.
- Duplicates: `:93-99`: "Fail closed on duplicate frontmatter keys (WP09, FR-007/C-002) … a future `typ`/config change cannot silently reintroduce YAML last-wins semantics". Commit `a76fb64dcd` (Refs #3372). Tests in `tests/test_frontmatter_dup_key.py`, including `test_nested_duplicate_key_falls_back_to_named_error` (`:77`) and `test_allow_duplicate_keys_is_pinned_false` (`:98`).
- Spec: YAML 1.2.2 §3.2.1.1, "the content of a mapping node is an unordered set of key/value node pairs, with the restriction that each of the keys is unique".

**Verdict.** INTENTIONAL-GUARD, and SPEC-MANDATED for duplicates.
- `startswith("---")` also accepts `----x` as an opening fence. That is lax but harmless: a CORE-ACCIDENT. The service matches it cheaply, so keep it.
- Universal newlines is Python's text-mode default, but every core reader shares it, so match.

**Recommendation:** match (done).

### 3. Task-file globbing: matched; two sub-accidents

**Mechanism.**
- Roster: `subtask_rows.py:271-276`: `re.compile(rf"^{wp_id}(?:[-_.]|\.md$)", re.IGNORECASE)` over `tasks_dir.glob("*.md")`, README excluded, 0 or more than 1 matches → error.
- Dependencies: `status/emit.py:591-607` `_match_wp_files`, the same pattern **without** `IGNORECASE`. More than one match → `TransitionError` → unresolvable (`emit.py:385-394`); zero matches → no dependencies (`:395-396`).

**Evidence.**
- Commit `ea3c628efa` "fail closed when a work package's dependency file is ambiguous": "the dependency gate opened precisely when it could not tell what the dependencies were … `_declared_dependencies` now raises TransitionError on a multi-match".
- Docstring `emit.py:592-600`.

**Verdict.** INTENTIONAL-GUARD for `*.md`, the word boundary and ambiguity. Two accidents sit underneath:
- **3b:** `Path.glob` yields directories and dangling symlinks: a STACK-ACCIDENT. It only ever adds ambiguity, so it errs to the fail-closed side of a designed guard. Keep counting names, which is also simpler than filtering.
- **3c:** the roster locator is case-insensitive and the dependency locator is case-sensitive: a CORE-ACCIDENT. On Linux, `wp02-x.md` is WP02's file for the roster guard but not for the dependency guard.

**Recommendation for 3c:** match case-insensitively in both locators. That is a superset of matches, so it can only add refusals.
- Ruling **R26:** "Both task-file locators match the work-package id case-insensitively. Core's dependency locator is case-sensitive (core issue I5 (spec-kitty/spec-kitty#7443)); the service's broader match can only make a declaration ambiguous or found, never hide one."

### 4. Own `meta.json` with a BOM: matched

**Mechanism.**
- `kernel/meta_decode.py:76-79` decodes bytes as strict `utf-8`, then `json.loads`. The BOM becomes `JSONDecodeError`, which is in the documented malformed set (`:13-21`, `_DECODE_ERRORS` `:53`).
- `mission_metadata.load_meta` defaults to `encoding="utf-8"`. The docstring (`:325-333`) says "Combine with `encoding="utf-8-sig"` for the BOM-tolerant decode of the legacy task-helper contract".
- `load_meta_strict` (`:466-475`) is BOM-tolerant by default.

**Verdict.** INTENTIONAL in the documented sense (BOM tolerance is opt-in per contract, and the canonical contract "preserves the historical default"). The project is internally inconsistent, though: frontmatter tolerates a BOM on purpose (#48).

**Recommendation:** match (404). A divergence would make the service append to a Mission core reports as absent: real split-brain. Optional issue I7 (spec-kitty/spec-kitty#7445).

### 5. Status-log line splitting: CORE-ACCIDENT

**Mechanism.**
- `status/store.py:712-733` `read_events` → `path.read_text(encoding="utf-8")` → `_partition_event_stream_from_text`, which iterates `content.splitlines()` (`:646`) and calls `json.loads(raw_line.strip())` (`:647-653`).
- `read_events_raw` (`:520-545`) iterates the file object (`:533-534`), which splits on `\n`/`\r`/`\r\n` only.

**Evidence.**
- `git log -S "content.splitlines()"` → `4ca1fc682e` "fix: make transactional status reads read-only". That refactor replaced `for line_number, raw_line in enumerate(fh, …)` with `splitlines()`. Nothing in the commit concerns Unicode line boundaries; the behaviour change was incidental.
- No core test pins it.
- RFC 8259 permits unescaped U+2028/U+0085 inside JSON strings.
- Core's writer never emits them raw: `json.dumps` defaults to `ensure_ascii=True` (`store.py:300`), and the service's byte-exact encoder doesn't either.
- Reproduced (`repro_log.py`): a row with a raw U+2028 in `reason` gives `read_events` → `StoreError: Invalid JSON on line 1` while `read_events_raw` → `ok 1 rows`. Same for U+0085.

**Verdict.** CORE-ACCIDENT (Python `str.splitlines()` semantics, introduced by a refactor).

**Practical impact.** Only hand edits or foreign writers produce such a log, and core cannot read it in any status command. Whatever the service does, that Mission is already broken for core. Copying `splitlines` (as now) encodes the accident; diverging fully (universal newlines only) gives 201 on logs core rejects.

**Recommendation.** Stop modelling `splitlines`. Treat any raw U+0085, U+2028, U+2029, VT, FF, FS, GS or RS byte sequence anywhere in the log as unreducible (500), and split on `\n`, `\r\n`, `\r` (both core readers agree on these).
- This is one check, simpler than the current separator table. It refuses everything core refuses here, plus a few logs core reads (FF after a row, U+2028 between rows): fail-closed residue.
- Ruling **R24:** "A status log containing a raw U+0085, U+2028, U+2029, VT, FF, FS, GS or RS is unreducible (`500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`). Core's `read_events` splits rows with Python `str.splitlines()` (an accident of `4ca1fc682e`; core issue I1 (spec-kitty/spec-kitty#7439)) while its `read_events_raw` does not; neither core nor the service ever writes these characters unescaped."
- Keeping today's exact port is acceptable as a stopgap (it is never fail-open), but it should not be recorded as core's contract.

### 6. Coordination-routed Mission (R16)

**Mechanism.** `status/mission_write.py:188-205` `mission_lock_key`: "A coordination-routed Mission (`coordination_branch` recorded in …) [is keyed by] the coordination directory name `<slug>-<mid8>`". The cycle-1 probe `out/p2b_coord_routed.json`: core ACKs and appends to the primary log under lock key `999-status-transition-parity-01J00000`, not the directory name.

**Verdict.** INTENTIONAL in core (documented). The service's `422` is a contract decision (decision 6; the ADR-0007 surface split), not a copy of anything. If the service appended under the plain key, the two writers would hold different locks on one file: a lost-update hazard.

**Recommendation:** keep R16. Core's acceptance is not a bug.

### 7. Tags (R19) and the untagged `=`

**Mechanism.**
- ruamel round-trip constructs a `TaggedScalar` for an explicit shorthand `!!str`, for non-yaml.org tags, and for an implicitly resolved plain `=`. The latter is ruamel's resolver still mapping `=` to `tag:yaml.org,2002:value`, a YAML 1.1 type absent from the 1.2 core schema.
- `WPMetadata` then rejects it for `str` fields, and `_coerce_declared_dependencies` (`emit.py:405-417`) rejects it for dependencies.

**Evidence.**
- YAML 1.2.2 §10.3.2 (core schema) has no `value` type: `=` is a plain `str`.
- `!!str 5` is the string `"5"` by definition (§10.1.1.3).
- Core never chose either behaviour: there is no test, and `FrontmatterManager` sets `preserve_quotes=False` (`frontmatter.py:90`) without addressing tags.
- Core accepting `!!int 12` (`repro_yaml.out`) is spec-correct.

**Verdict.** STACK-ACCIDENT for the refusals; core is spec-correct for its acceptances.

**Recommendation.** Keep R19 (a fail-closed superset; no real file uses tags). Also stop modelling the untagged `=`: today `Frontmatter.RoundTripSafeConstructor` builds a `TaggedScalar` and `FactValues.pydanticList` turns it into a list of code points. That mirrors pydantic's lax coercion of a ruamel object, two accidents deep. Replace it with: a plain scalar `=` anywhere → unresolvable.
- Ruling **R27:** "A plain (unquoted) `=` scalar anywhere in a work package's frontmatter makes its facts unresolvable. Core's ruamel resolves it to the YAML 1.1 `value` type (a stack accident, I6) and core's guards then refuse it in some positions; the service refuses it everywhere."

### 8. Scanner gaps (R20)

`repro_yaml.out` against YAML 1.2.2:
- Tab in indentation is forbidden (§6.1): SPEC-MANDATED. Ruamel and PyYAML also reject in-line tabs (`agent: a\tb`, `agent\t: a`), which the spec allows (s-white). That is a PyYAML-lineage STACK-ACCIDENT.
- U+2028 is an ordinary printable character in 1.2 (§5.4 makes only LF/CR line breaks). Core reads `'a b'`, which is correct. U+0085 is folded to a space by ruamel (`'a b'`), a YAML 1.1 relic: STACK.
- `%YAML 1.2` with no document: §9.2 `l-directive-document ::= l-directive+ l-explicit-document`. The refusal is SPEC-MANDATED.
- Anchors, aliases and `...` are valid, and core reads them correctly.

**Recommendation:** keep R20 as the fail-closed plain-subset restriction. Nothing to file against core except the ruamel deviations in I6.

### 9. Duplicate keys under Python equality

**Mechanism.** ruamel `RoundTripConstructor.construct_mapping` (`ruamel/yaml/constructor.py:1300-1350`) constructs each key, wraps lists and dicts in `CommentedKeySeq`/`CommentedKeyMap`, then `check_mapping_key` (`:256-283`) tests `if key in mapping`. That is Python `hash` plus `==`, so `1 == 1.0 == True` and `-0.0 == 0` collide.

**Evidence.**
- YAML 1.2.2 §3.2.1.3: "Two nodes must have the same tag and content to be equal … Two scalars are equal only when their tags and canonical forms are equal character-by-character." `1` (`!!int`) and `1.0` (`!!float`) are different nodes, so `{1: a, 1.0: b}` is a valid mapping. PyYAML reads it (`repro_yaml.out`).
- The genuinely spec-equal pairs (`1` vs `0x1` vs `+1`) are refused by core too, which is correct.
- Core's guard (`frontmatter.py:93-99`, #3372) targets YAML last-wins semantics. Its tests use identical spellings only.

**Verdict.** STACK-ACCIDENT, but a benign one: a Python dict cannot hold `1` and `1.0` as two keys, so refusing is the only faithful outcome for core. No core issue; core's behaviour is safe.

**Recommendation.** Do not keep porting Python equality (`Frontmatter.rejectPythonEqualDuplicateKeys`, `pythonKey`, `pythonKeysEqual`, `pythonMapsEqual`, the NaN carve-out). Replace them with one restriction:
- Ruling **R22:** "Every mapping key in a work package's frontmatter, at any depth, must be a plain or quoted scalar that resolves to a string under the YAML 1.2 core schema (§10.3.2). A null, boolean, integer, float (including `.nan`/`.inf`), timestamp, `=` or `<<`-as-non-merge key, or a sequence or mapping used as a key, makes the facts unresolvable. Core's ruamel loader compares keys with Python `==`/`hash` (`1 == 1.0 == true`, one shared NaN object, unhashable nested keys), a stack accident; no real work-package frontmatter (0 of 4,665) uses a non-string key."
- Judge "string" by the 1.2 core-schema regexes (int `[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+`, float, null `~|null|Null|NULL|""`, bool `true|false` in its three casings), **not** by SnakeYAML's 1.1 resolver. Otherwise `0o10` (a string to SnakeYAML, int 8 to ruamel) would slip through.
- After R22, among string keys, Python `==` is Java `String.equals`, so SnakeYAML's own `allowDuplicateKeys(false)` is exact.
- It closes classes 9, 13 and 14 and the residue items "timestamp keys", "`=` vs `'='` in complex keys" and "map keys differing only in order".

### 10. Duplicate `<<` merge keys: SPEC-MANDATED

**Mechanism.** `ruamel/yaml/constructor.py:1250-1262`: "Duplicate merge keys are never allowed, not even when `.allow_duplicate_keys` is set to True".

**Evidence.**
- ruamel changelog 0.18.15 (`ruamel_yaml-0.19.1.dist-info/METADATA:229`): "duplicate merge keys are never allowed".
- The merge type (yaml.org/type/merge.html) makes `<<` an ordinary key with the merge tag. Two of them are equal key nodes (same tag, same canonical form), hence a duplicate under §3.2.1.1.
- PyYAML's acceptance is the lax one.

**Verdict.** SPEC-MANDATED, and deliberate in ruamel. **Recommendation:** match (done).

### 11. Flow-context `:` (R21)

`repro_flow.py`, against YAML 1.2.2 §7.4.2:
- `{a:[]}`, `[{a:[b]}]`, `{b:[]}` are refused by ruamel and accepted by PyYAML. Per spec, after a plain (non-JSON-like) key, `c-ns-flow-map-separate-value` requires whitespace before the value node, or an empty node. So these refusals are SPEC-MANDATED, and PyYAML/SnakeYAML are lax.
- `['a':b]` and `["a":b]` are refused by ruamel. Per spec, Examples 7.18/7.21 allow a JSON-like key with an adjacent value inside a single-pair flow-sequence entry (`- [ {JSON: like}:adjacent ]`); PyYAML reads `[{'a': 'b'}]`. A **ruamel bug**: inconsistently, ruamel accepts `{'a':b}`.
- `[a:]`: ruamel gives `['a:']`, PyYAML gives `[{a: None}]`. Per spec, `:` followed by `]` cannot be part of a plain scalar (`ns-plain-char`), so the spec result is `[{a: null}]`. Another **ruamel bug**.

**Verdict.** Mixed: SPEC-MANDATED plus STACK-ACCIDENT. **Recommendation:** keep R21. It covers both without modelling either. Residue such as `{'a':b}`, where core is spec-correct, stays fail-closed.

### 12. Block-scalar leading blank lines: STACK-ACCIDENT, do not port

**Mechanism.** `ruamel/yaml/scanner.py:1299-1322` `scan_block_scalar_indentation`:
- `first_indent` is the column at the first line break, i.e. the length of line 1 when line 1 is spaces-only.
- `max_indent` is the largest column reached on blank lines and on the first content line.
- If `first_indent > 0 and max_indent > first_indent`, it raises "more indented follow up line than first in a block scalar".
- It runs only without an indentation indicator.

**Evidence.**
- YAML 1.2.2 §8.1.1.1: "If no indentation indicator is given, then the content indentation level is equal to the number of leading spaces on the first non-empty line of the contents. If there is no non-empty line then the content indentation level is equal to the number of spaces on the longest line. **It is an error for any of the leading empty lines to contain more spaces than the first non-empty line.**"
- Reproduced (`repro_yaml.out`):
  - `description: |\n \n  x` (the leading empty line is *shorter* than the first non-empty line, spec-valid): core refuses, PyYAML reads. The service also refuses today (`jcases/blk_short_first`).
  - `description: |\n \n   \nagent: x` (no non-empty line, spec-valid): core refuses, PyYAML reads, the service reads (cycle-7 finding 2).
  - The spec-mandated error (`|\n   \n  x`) is refused by ruamel *and* PyYAML through ordinary parsing (`expected <block end>`). SnakeYAML behaves like PyYAML, so the service matches it without any special rule.
- This check is not in PyYAML. It is a ruamel addition, stricter than the spec, with no core test or design note.

**Verdict.** STACK-ACCIDENT, file-wide: core cannot read the file at all.

**Recommendation.** Withdraw cycle-7 finding 2 as worded ("copy ruamel's rule exactly"): it would encode a ruamel bug as the contract. Delete `Frontmatter.validateBlockScalar`'s ruamel model and use a one-line restriction instead:
- Ruling **R23:** "A `|` or `>` block scalar without an indentation indicator whose first line after the header is non-empty and consists only of spaces makes the frontmatter unresolvable. Core's ruamel refuses a subset of these files with a check stricter than YAML 1.2.2 §8.1.1.1 (a stack accident, I6); the service refuses the whole shape rather than modelling the check."
- This is a superset, because ruamel's check needs `first_indent > 0`, which happens only in that shape. It keeps every real block scalar (0 of 8 affected) and the existing controls (`|\n\n \n  x`, first line empty) readable.
- Residue: `|\n \n x`, which core reads.

### 13. NaN keys

**Mechanism.** `ruamel/yaml/constructor.py:480` `nan_value = -inf_value / inf_value`, returned by every `.nan` spelling (`:493`, `:1128`), so `key in mapping` succeeds by identity.

**Evidence.** §3.2.1.3: scalars are equal when tag and canonical form are equal. The canonical form of NaN is `.nan` (§10.2.1.4), so two `.nan` keys are a spec duplicate, and core refuses them correctly (by coincidence). `[.nan, 1]` vs `[.nan, 1.0]` differ per spec (int vs float) and core refuses them through Python equality.

**Verdict.** SPEC-MANDATED (top-level) and STACK (nested). **Recommendation:** R22 refuses every numeric key, so cycle-7 finding 1 needs no NaN-specific code.

### 14. Complex keys core cannot hash

**Mechanism.** `ruamel/yaml/constructor.py:1326-1350`: `CommentedKeySeq` is a tuple (hashable only if its elements are). `CommentedKeyMap.__hash__` is `hash(tuple(self.items()))` (`ruamel/yaml/comments.py:1063-1064`). The `key in maptyp` test in `check_mapping_key` then raises `TypeError: unhashable type: 'CommentedSeq'`, which `frontmatter.py:144-145` turns into "Invalid YAML".

**Evidence.** YAML allows any node as a key (§3.2.1.1). PyYAML refuses these too (with the same Python limitation); core never chose to.

**Verdict.** STACK-ACCIDENT, file-wide. **Recommendation:** R22 refuses all complex keys, so cycle-7 finding 3 is moot.

Related: `CommentedKeyMap` hashes order-dependently, but `Mapping.__eq__` is order-independent. Two map keys that differ only in order are therefore **equal but usually hash differently**, so core reads `{? {a: 1, b: 2}: x, ? {b: 2, a: 1}: y}` (`repro_yaml.out`), a spec duplicate. A ruamel bug (I6); the service's refusal is spec-correct.

### 15. `shell_pid` coercion

**Mechanism.** `wp_metadata.py:384-390` `coerce_shell_pid`: `None`/`""` → `None`, else `int(v)`. Python's `int()` accepts Unicode-14 Nd digits, `_` separators, surrounding whitespace, `bool` and truncated floats. It raises `OverflowError` on `.inf` (caught by the roster's `except Exception`) and `ValueError` past 4300 digits.

**Evidence.** The docstring reads "Coerce string shell_pid from YAML frontmatter to int." Commits `ea12637d78` (#529, "typed WPMetadata") and `c181cd8c00`. The real corpus has only ASCII-digit strings and `""` (about 1,434 files). Since WP05 (`frontmatter.py:24-33`), frontmatter `shell_pid` is legacy and read nowhere but this validation.

**Verdict.** STACK-ACCIDENT for every form beyond quoted ASCII digits.

**Recommendation.** Accept exactly `null`, `''`, a YAML int, or a string that after Python strip matches `^[+-]?[0-9]{1,4300}$`. Refuse everything else (roster → R15). Delete `POST_PYTHON_311_DECIMAL_RANGES`, the `isPython311DecimalDigit` logic and the 660-code-point SHA test.
- Ruling **R28:** "`shell_pid` is valid when absent, null, empty, an integer, or a string of 1–4300 ASCII digits with an optional sign and surrounding whitespace; anything else makes the roster unresolvable (R15). Core coerces with Python `int()`, whose extra forms (Unicode digits, `_`, booleans, floats) are a stack accident no real file uses."

**15b (NEW, fail-open, verified).** `shell_pid: '<4301 ones>'`: core's roster raises `SubtaskRosterResolutionError <- ValidationError` (`repro_bigint.out`), while the service resolves the roster as `[]` (`jcases/pid4301`). R28's 4300 cap fixes it.

### 16. Integer and nesting limits

- **Status log.** `json.loads` raises `ValueError: Exceeds the limit (4300 digits)` and `RecursionError` at about depth 1000. That depth depends on the caller's stack, i.e. the environment.
- Both escape the `except json.JSONDecodeError` at `store.py:652` (`repro_log.py`), so core crashes with a non-`StoreError` exception. The docstring at `:640-641` promises `StoreError`: a minor CORE-BUG (I1). The limits themselves are CPython DoS guards (CVE-2020-10735), which core never chose: STACK.
- **Recommendation:** keep the 4300-digit integer cap and the unbounded floats. Lower the service's JSON nesting cap from 1000 to a fixed 256, documented as a resource bound. Then the service never accepts a depth that core's stack would reject (core's real threshold is below 1000 inside the emit pipeline). No real row nests deeper than about 5.

**16b (NEW, fail-open, verified).**
- `agent: <4301-digit int>` in WP01: core's `read_frontmatter` → `FrontmatterError: … Exceeds the limit (4300 digits)`, so both guards refuse. The service (`jcases/int4301`) resolves the roster `[]` and the dependencies `[]`.
- The same holds for any field. On WP02 it opens the dependency guard (core: `dependencies_unsatisfied`).
- Base-16 and base-8 ints are not limited in CPython, so only decimal counts.
- Ruling **R29:** "A decimal integer scalar with more than 4300 digits anywhere in a work package's frontmatter makes its facts unresolvable. Core's Python refuses to construct it (`sys.int_max_str_digits`, a stack limit), which makes the whole file unreadable to core."

---

## B. Fail-closed rulings and residue: is core's acceptance a bug?

- **R15:** both sides refuse; only the response shape differs. No divergence in verdict.
- **R16:** see class 6. Core is not wrong.
- **R17** (separate git dir, submodules): core writes status there correctly. The service refuses by FR-009's root-checkout rule. A deliberate scope limit, not a core bug.
- **R18** (16 MiB): core reads any size. That is not a bug; the service bound keeps a read under the Mission lock bounded.
- **R19–R21:** see classes 7, 8, 11. R21 residue:
  - `:` alone → core `{None: None}`. Spec-correct (implicit empty key and value).
  - `<<` as a value → ruamel `TaggedScalar(merge)`, the YAML 1.1 merge relic; in pure 1.2 it is the string `"<<"`. STACK, and core refuses it only in `str` fields.
  - `"\/"` → `/`. Spec-correct: 1.2 added `\/` (§5.7). It is a SnakeYAML 1.1 gap.
  - `[:x]`, `[a?]`, `[-?]`, `[::]` → plain strings. Spec-correct (`ns-plain-first` allows an indicator followed by a safe character).
  - Map keys differing in order → **core wrong** (class 14 note).
  - Nesting 49+ → core has no limit. Not a bug; this is the service's SnakeYAML default.
- **Review-7 residue:**
  - Timestamp keys for the same instant: YAML's timestamp type says an omitted zone is UTC, so the two keys are equal and a duplicate. **Core is wrong** (Python's naive != aware). The service is correct.
  - `=` vs `'='` in complex keys: under the 1.2 core schema both are `str` `=`, so this is a duplicate. Core reads it because of ruamel's 1.1 `value` tag. **Core wrong**, service correct.
  - Merge-source spreads: merge semantics make overrides legal, so core is correct and the service's refusal is fail-closed residue. With R20's anchor ban only inline merges remain.
- **Older residue:**
  - YAML 1.1 vs 1.2 resolution: this is **the service's bug**. SnakeYAML resolves `1:20` as int 80, `010` as 8 and `0o10` as a string; ruamel 1.2 gives `"1:20"`, 10 and 8. It can be fail-open, for example a roster id `80` vs `"1:20"`, or a dependency string vs an int. Fix in the service (a 1.2 core-schema `Resolver` like `Yaml12Resolver`, extended from the six boolean words to ints and floats), or refuse plain scalars matching 1.1-only numeric forms. Not a core issue.
  - Python `str()` rendering of ids: class 1b (core bug).
  - `RootCheckout` first `[core]` / quoted `bare`: a service-side parser defect (core asks git). Fix in the service.
- **Decision 9a (non-finite):** `store.py:300` calls `json.dumps(…, sort_keys=True)` with the default `allow_nan=True`, so a float NaN or infinity in `evidence`/`policy_metadata` is written as the bare token `NaN`/`Infinity`. That is not JSON (RFC 8259 §6), and other consumers of the log, such as Jackson, refuse it by default. The repo's own `persistence.py` refuses non-finite numbers. **CORE-BUG** (I3).
  - The service's `REQUEST_MALFORMED` is right.
  - Also check that the service's log *reader* behaves deliberately on a core-written `Infinity`: research §3 says event-log's `PythonJson` decodes non-finite values, so confirm `StatusRowDecoder` does the same or records the refusal.
- **Decision 9b (mixed naive/aware):** `src/specify_cli/events/sanitizer.py:97-116`. Each timestamp's parse failure is swallowed on purpose (`:97-113`, "could not parse … field removed"), but `int((ended - started).total_seconds())` at `:116` is outside both `try` blocks. A naive/aware pair raises `TypeError` and aborts the emit. That contradicts the function's own swallow-per-field intent: **CORE-BUG** (I2). The service's refusal is right.
- **Decision 10 (CRLF):** `store.py:369` `os.fdopen(fd, "w", encoding="utf-8")` uses text mode, which writes `os.linesep` (CRLF) on Windows. A STACK-ACCIDENT: log bytes become platform-dependent, which defeats byte-level replay equality across OSes. The service's LF-always policy plus a tolerant reader is right. Folded into I3.
- **C-006 (torn tail):** `store.py:357` (`if existing and not existing.endswith("\n"): existing += "\n"`, commit `eb3539f4d7`, #946) is a deliberate normalization of a *complete* last row that lacks its LF. A genuinely partial row fails `read_events` before any append. Core is not wrong. The service's refusal is the operator ruling of 2026-10-31. Keep.

---

## Issue drafts (spec-kitty/spec-kitty)

Every reproduction runs from a core checkout at `512567ee` with `uv run python` (or `.venv/bin/python`), using core's own entry points.

### I1. `read_events` splits JSONL rows on Unicode line boundaries and leaks non-`StoreError` exceptions

**Title:** status: `read_events` uses `str.splitlines()`, so a valid JSONL row containing U+2028/U+0085 is "Invalid JSON"; integer/nesting limits escape as `ValueError`/`RecursionError`

**Body:**
`store._partition_event_stream_from_text` (`src/specify_cli/status/store.py:646`) iterates `content.splitlines()`. That splits on `\x0b \x0c \x1c \x1d \x1e \x85    ` as well as `\n`/`\r`. RFC 8259 allows U+2028 and U+0085 unescaped inside strings, so a valid row written by any non-Python tool or a hand edit becomes "Invalid JSON". `read_events_raw` (`:533-534`) iterates the file and reads the same log fine. The split arrived with `4ca1fc682e` ("make transactional status reads read-only"), which replaced file iteration; nothing suggests the change was intended.

Separately, `json.loads` can raise `ValueError` (Python's 4300-digit integer limit) or `RecursionError` (deep nesting). Neither is caught by `except json.JSONDecodeError` (`:652`), so the documented `StoreError` contract (`:640-641`) does not hold.

```python
import json, pathlib, tempfile
from specify_cli.status import store
row = {"actor": "a", "at": "2026-01-01T00:00:00+00:00", "event_id": "01J0000000000000000000000A",
       "evidence": None, "execution_mode": "worktree", "force": False, "from_lane": "planned",
       "mission_slug": "m", "reason": "x y", "to_lane": "claimed", "wp_id": "WP01"}
d = pathlib.Path(tempfile.mkdtemp())
(d / "status.events.jsonl").write_text(json.dumps(row, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
print(len(store.read_events_raw(d)))   # 1
store.read_events(d)                   # StoreError: Invalid JSON on line 1: Unterminated string ...
(d / "status.events.jsonl").write_text(json.dumps(dict(row, reason="x"), sort_keys=True).replace('"evidence": null', '"evidence": ' + "1" * 4301) + "\n")
store.read_events(d)                   # ValueError: Exceeds the limit (4300 digits) ... (not StoreError)
```

**Expected:** split rows on `\n` (tolerating `\r\n`/`\r`) as `read_events_raw` does, and wrap `ValueError`/`RecursionError` from `json.loads` in `StoreError`.

**Impact:** low frequency (core's writer escapes non-ASCII), but a Mission whose log carries such a row is unreadable to every status command. The events-repo write service records this as a deliberate divergence (R24) pending a fix.

### I2. Mixed naive/aware session timestamps crash `sanitize_event_for_log`

**Title:** events: `sanitize_event_for_log` raises an uncaught `TypeError` for a naive `session_started_at` paired with an aware `session_ended_at`

**Body:** `src/specify_cli/events/sanitizer.py:97-113` deliberately swallows a parse failure per field ("could not parse … field removed"). The subtraction at `:116`, `int((ended - started).total_seconds())`, is outside both `try` blocks. When one timestamp is naive and the other aware, both parse and the subtraction raises `TypeError: can't subtract offset-naive and offset-aware datetimes`. That aborts serialization, and so the whole emit.

```python
from specify_cli.events.sanitizer import sanitize_event_for_log
sanitize_event_for_log({"session_started_at": "2026-01-01T00:00:00", "session_ended_at": "2026-01-01T00:01:00+00:00"})
# TypeError: can't subtract offset-naive and offset-aware datetimes
```

**Expected:** treat the pair as unparseable (drop both, emit no `session_duration_s`), consistent with the per-field policy, or normalize naive timestamps to UTC. Add a test next to `tests/specify_cli/events/test_sanitizer.py:184-219`.

### I3. The status serializer writes non-standard JSON (`NaN`/`Infinity`) and platform-dependent newlines

**Title:** status: `serialize_event_line` writes `NaN`/`Infinity` (`allow_nan` default) and `append_raw_rows_atomic` writes CRLF on Windows

**Body:**
- `store.py:300` `json.dumps(sanitize_event_for_log(event_dict), sort_keys=True)` keeps `allow_nan=True`, so a non-finite float in `evidence`/`policy_metadata` is written as the bare token `NaN`, `Infinity` or `-Infinity`. That is not JSON (RFC 8259 §6). The log is a cross-language contract, and non-Python readers such as Jackson reject these tokens by default.
- `store.py:369` opens the temp file in text mode (`os.fdopen(fd, "w", encoding="utf-8")`), so Windows writes CRLF: the same events produce different bytes per OS.

```python
from specify_cli.status.store import serialize_event_line
print(serialize_event_line({"evidence": {"x": float("inf")}, "wp_id": "WP01"}))
# {"evidence": {"x": Infinity}, "wp_id": "WP01"}   (verified at 512567ee)
```

**Expected:** `allow_nan=False`, refusing non-finite input before the write. Open with `newline="\n"` (or write bytes).

### I4. `authored_subtask_roster` turns map-shaped subtask entries into unsatisfiable Python-repr ids

**Title:** core: `normalize_authored_subtask_roster` `str()`-coerces non-scalar `subtasks:` entries, so the for_review gate can never pass

**Body:** `src/specify_cli/core/subtask_rows.py:365-374` applies `str(raw).strip()` to every authored entry. That is meant for ints (`1` → `"1"`), but WPs that author subtasks as maps get roster ids like `"{'id': 'T024', 'title': '…', 'status': 'planned'}"`. Examples: `kitty-specs/058-hybrid-prompt-and-shim-agent-surface/tasks/WP02…WP06` in this repo, with 28 entries. No `mark-status` event can match such an id, so `in_progress -> for_review` is refused unless forced. The same happens for lists, dates and floats (`1e20` → `"1e+20"`).

```python
import pathlib, tempfile
from specify_cli.core.subtask_rows import authored_subtask_roster
d = pathlib.Path(tempfile.mkdtemp()); (d / "tasks").mkdir()
(d / "tasks" / "WP01.md").write_text("---\nwork_package_id: WP01\nsubtasks:\n- id: T001\n  title: x\n---\n")
print(authored_subtask_roster(d, "WP01"))   # ["{'id': 'T001', 'title': 'x'}"]
```

**Expected:** read `id` from map entries (if that shape is supported), or raise `SubtaskRosterResolutionError` for any entry that is not `str`/`int`, so the failure is legible instead of silent.

### I5. The two work-package file locators disagree on case

**Title:** core: roster locator matches `WPxx` case-insensitively, dependency locator does not

**Body:**
- `subtask_rows._locate_wp_file` (`src/specify_cli/core/subtask_rows.py:271`) compiles its pattern with `re.IGNORECASE`; `status.emit._match_wp_files` (`src/specify_cli/status/emit.py:606`) does not. On a case-sensitive filesystem, `tasks/wp02-x.md` is WP02's file for the subtask gate but absent for the dependency gate: its `dependencies:` are silently ignored, a "declares nothing" verdict.
- Also, `Path.glob("*.md")` (`:274`, `:607`) yields directories and dangling symlinks, which then count toward "ambiguous".

```python
import pathlib, tempfile
from specify_cli.core.subtask_rows import _locate_wp_file
from specify_cli.status.emit import _match_wp_files
d = pathlib.Path(tempfile.mkdtemp()); (d / "tasks").mkdir()
(d / "tasks" / "wp02-x.md").write_text("---\nwork_package_id: WP02\ndependencies: [WP01]\nsubtasks: []\n---\n")
print(_locate_wp_file(d, "WP02"))   # .../tasks/wp02-x.md
print(_match_wp_files(d, "WP02"))   # []  -> dependencies treated as none declared
```

**Expected:** one shared locator, one case rule. Only regular files count; a non-file match is "ambiguous/unreadable", never silently skipped.

### I6. `FrontmatterManager.read` inherits ruamel deviations from YAML 1.2 (decide on a documented WP-frontmatter profile)

**Title:** frontmatter: ruamel 0.19.1 rejects some valid YAML 1.2 and accepts some duplicates; document a restricted WP-frontmatter profile

**Body:** `FrontmatterManager.read` (`src/specify_cli/frontmatter.py:101-161`) delegates to ruamel `YAML()`. Measured at `512567ee` against YAML 1.2.2 and PyYAML 6.0.3:

1. **Rejects valid input:**
   - `description: |\n \n  x` and `description: |\n \n   \nagent: x` fail with "more indented follow up line than first in a block scalar" (`ruamel/yaml/scanner.py:1317-1321`). §8.1.1.1 forbids only leading empty lines *longer* than the first non-empty line.
   - `history: ['a':b]` fails, although §7.4.2 Example 7.21 allows a JSON-like key with an adjacent value.
2. **Misreads:**
   - `[a:]` → `['a:']`; the spec gives `[{a: null}]`.
   - `agent: a\x85b` → `'a b'` (NEL treated as a line break, YAML 1.1).
   - A plain `=` resolves to the YAML 1.1 `value` type, so `description: =` fails `WPMetadata` validation.
   - `!!str 5` becomes a `TaggedScalar`, not `"5"`.
3. **Misses duplicates the spec defines (§3.2.1.3):**
   - Mapping keys that differ only in order (`CommentedKeyMap.__hash__` is order-dependent while `__eq__` is not).
   - Timestamps denoting the same instant (`2001-12-14t21:59:43.10-05:00` vs `2001-12-15 2:59:43.10`).
4. **File-wide failures from Python semantics:**
   - Nested-collection keys raise `unhashable type`.
   - A decimal int with more than 4300 digits raises `Exceeds the limit (4300 digits)`.

```python
import pathlib, tempfile
from specify_cli.frontmatter import read_frontmatter
def rd(fm):
    p = pathlib.Path(tempfile.mkdtemp()) / "WP01.md"; p.write_text("---\n" + fm + "---\n")
    try: return read_frontmatter(p)[0]
    except Exception as e: return f"ERR {e}"[:120]
print(rd("description: |\n \n  x\n"))                       # ERR … more indented follow up line …
print(rd("history: ['a':b]\n"))                             # ERR … while parsing a flow sequence
print(rd("history:\n- {? {a: 1, b: 2}: x, ? {b: 2, a: 1}: y}\n"))   # reads: duplicate not detected
print(rd("description: =\n"))                               # TaggedScalar(value='=', tag=…:value)
```

**Proposal:** none of 4,665 real WP frontmatters uses these constructs. Rather than chase ruamel, document a WP-frontmatter profile and refuse everything outside it at this boundary:
- block mappings and sequences;
- string keys;
- no tags, anchors, directives or tabs;
- no flow `:` without a following space.

The events-repo write service already enforces this profile (its rulings R19–R23), so the two would then agree by construction. Report items 1–3 upstream to ruamel (sourceforge `ruamel-yaml` tickets).

### I7 (low priority). BOM handling differs between `meta.json` readers

**Title:** mission metadata: canonical `load_meta` rejects a UTF-8 BOM, while `load_meta_strict` and frontmatter accept it

**Body:**
- `kernel/meta_decode.py:76-79` decodes strict UTF-8, so a BOM-prefixed `meta.json` is "malformed" and the Mission is invisible to `resolve_handle_to_read_path`.
- `mission_metadata.load_meta_strict` (`:466-475`) and `FrontmatterManager.read` (`frontmatter.py:116`, #48) tolerate a BOM. A Windows editor that adds one makes the Mission vanish from status and emit paths while the task helpers still see it.

**Expected:** pick one policy for `meta.json` (tolerate it, or refuse with a legible "BOM" error).

### I8 (optional, design question). Roster gate validates the whole `WPMetadata`; the dependency gate deliberately does not

**Title:** status: should an unrelated `WPMetadata` defect block `for_review`?

**Body:**
- `_declared_dependencies` (`src/specify_cli/status/emit.py:331-337`) avoids whole-model validation because it "would refuse status writes for defects unrelated to dependencies".
- `authored_subtask_roster` (`src/specify_cli/core/subtask_rows.py:319`) runs `WPMetadata.model_validate`, so an unknown key blocks the unforced `in_progress -> for_review`. Examples: `pre_review_test_scope` in `kitty-specs/pin-absent-lane-wording-7200-01M6P3HX/tasks/WP01-pin-absence-sentence.md`, or a `base_commit: main`.

Is the asymmetry intended? If it is, say so in the roster docstring. If not, validate only `subtasks`.

---

## Changes this audit implies for WP08 (for the orchestrator)

1. **Fix before approval** (fail-open; the rulings are new, so record them):
   - R29: decimal int scalars with more than 4300 digits in frontmatter.
   - R28's 4300-digit cap on `shell_pid` strings.
2. **Replace accident ports with restrictions** (fewer lines, not more):
   - R22 (string keys) instead of the Python-equality walk and the NaN carve-out. Cycle-7 findings 1 and 3 become moot.
   - R23 instead of ruamel's block-scalar model. Withdraw cycle-7 finding 2 as worded.
   - R27 instead of the `=` `TaggedScalar` and code-point list.
   - R28 instead of the Unicode-14 digit table.
   - R25: roster entries must be `str` or `int`.
   - R26: case-insensitive locators.
   - R24 (log separator characters) instead of the `splitlines` port. Optional: the current port is never fail-open.
3. **Keep:** R15–R21, decision 9, decision 10 and C-006, with research §11 decision 18 rewording R19–R21 from "SnakeYAML vs ruamel gap" to the verdicts above (part spec-mandated, part ruamel bugs).
4. **Corpus:** cases whose expected outcome is a core accident move to `oracle: protocol`, with a note naming the ruling:
   - `[1.1]` roster;
   - `shell_pid` `'1_000'`/`'𝟙'`/`true`/`12.5`;
   - Unicode-digit cases;
   - Python-equal key cases.

   Core-oracle cases remain only where core's behaviour is an intentional guard or spec-mandated.
5. **File** I1–I5 (and I6–I8 as the maintainer sees fit) on spec-kitty/spec-kitty. Cite them in the rulings.
