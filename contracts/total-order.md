# Contract: Total Order of Event-Log Rows

**Status**: proposed · **Applies to**: rows in their CloudEvents (upcast) form ·
**Enforced by**: [`tests/test_total_order.py`](../tests/test_total_order.py) and the packaged
[`conformance/test_total_order.py`](../src/spec_kitty_events/conformance/test_total_order.py)
**Reference implementation**: [`spec_kitty_events.total_order`](../src/spec_kitty_events/total_order.py)
**Fixtures**: [`producer_contract/fixtures/total_order/`](../src/spec_kitty_events/producer_contract/fixtures/total_order/)

> Written for spec-kitty/spec-kitty#6185 and spec-kitty-events#92. It is the ordering
> option C of [ADR 0001](../docs/adr/0001-cloudevents-envelope.md) (Accepted 2026-10-15), with
> the total order that option C requires. The spec-kitty operator ruled option C and the
> maintainer confirmed it on acceptance of ADR 0001. Nothing here changes what any existing release accepts
> and no reducer uses it yet: the reducer sort-key switch is #92's.

This order is distinct from the pre-existing `spec_kitty_events.conflict.total_order_key`,
which keys `(lamportclock, event_id)` over the Pydantic `Event` model. This contract keys
`(h, c, t, i, s)` over CloudEvents-form row dictionaries and supersedes that key for rows
in that form; a consumer ordering CloudEvents rows should use this one, not
`total_order_key`.

## Why a defined total order

The ADR's option C orders by Lamport clock, then time, then id, falling back to
`(time, id)` for rows with no clock. Read as "use the clock when both rows have one,
otherwise `(time, id)`", that rule is **not transitive**: with `A` (clock 5, 10:00), `B`
(clockless, 10:30) and `C` (clock 3, 11:00), `C < A` by clock, `A < B` by time and
`B < C` by time, a cycle, so the sorted result depends on the order of the input. A
reducer built on it would not be deterministic. This contract replaces it with a
lexicographic key, which is total by construction.

## The key

For a row `r`, with the members of a CloudEvents structured-JSON row:

```
key(r) = ( h(r), c(r), t(r), i(r), s(r) )
```

| Part | Value |
|---|---|
| `h` | `1` if `r` has a `lamportclock` member, else `0`. Every clockless row sorts before every clocked row. |
| `c` | the `lamportclock`, else `0`. |
| `t` | `time` as an exact UTC instant. |
| `i` | `id` normalised by `normalize_event_id` (a ULID upper-cased; a UUID lower-cased and hyphenated). |
| `s` | `source`, the empty string when the member is absent or `null`. |

Keys are compared left to right, ascending. Strings (`i`, `s`) compare **by Unicode code
point** (not UTF-16 code unit). `nodeid` is not in the key.

### Clock

A row is *clockless* if and only if it has no `lamportclock` member. If the member is
present it must be an integer in `0..2^31-1` (the CloudEvents `Integer` range). `null`, a
boolean, a string, a negative number, a larger number, and a number written with a
decimal point or exponent (`5.0`, `5e0`, whatever its value) are not clockless: the row is
an *error row* (`clock_out_of_range`). Note that this treats a `null` clock as invalid,
although CloudEvents reads a `null` context attribute as unset; the stricter reading is
the one #6185 requires, and a clock must never be silently defaulted.

### Time

`time` must be an RFC 3339 `date-time`: `YYYY-MM-DD`, `T` or `t`, `hh:mm:ss`, optional
fraction of one to nine digits, then `Z`, `z` or `+hh:mm` / `-hh:mm`. A leap second
(`:60`) is the following second. Space separators, basic format, a missing offset, hour
24, trailing characters (a newline included), year `0000` and a fraction of more than nine digits are not accepted (`time_unparseable`; nine is
the precision of Go's `RFC3339Nano` and Java's `Instant`, so every implementation can
agree). An absent, `null`, empty or non-string `time` is also `time_unparseable`. The
instant is exact: `12:00:00+02:00` equals `10:00:00Z`, and nanoseconds are not rounded.

### Id and source

A missing, `null` or empty `id` is `id_missing`; an id that does not normalise (not a
valid 26-character ULID, a 36-character UUID, or a 32-character hex UUID) is
`id_not_normalizable`. A `source` that is present, not `null` and not a string is
`source_invalid`. A line that is not a JSON object is `row_not_object`. (`source_invalid`
and `row_not_object` are not in the #6185 design, which is silent on them; they are added
so the order is defined for every JSON input.)

## Procedure

Given the rows as read, in file order (each a parsed JSON value: objects with string keys,
as a JSON parser returns them; other host objects are outside the domain):

1. **Set aside error rows.** A row that cannot be keyed is set aside with its position
   in the input and a reason. The checks run in this order, so a row with several defects
   has one deterministic reason: object, id, clock, time, source. An error row is never
   dropped, never sorted, and never a survivor of step 3.
2. **Group** the remaining rows by `(s, i)`. Two spellings of one id in one source (case,
   hyphenation) are one group.
3. **Collapse.** Of each group, the survivor is the row whose serialisation (members
   sorted by key, separators `,` and `:`, every non-ASCII character escaped as `\uXXXX` with lower-case hex and surrogate pairs for astral code points, so a lone surrogate serialises too; integers and strings are exact, other numbers use the shortest round-trip form) has the smallest
   bytes, so the choice never depends on input position. Every other row of the group
   counts as collapsed. If the rows of a group differ in any member other than `id`, the
   group is also reported as a *conflict*, by its `(s, i)` key. A conflict is a report,
   not another bucket: its rows are already counted as one survivor plus collapsed.
   The survivor *bytes* are reproducible across languages only for integer- and
   string-valued rows: the sort key `(h, c, t, i, s)` carries no number, so the chosen
   **order** is language-independent regardless, but a non-Python conformance target may
   serialise a non-integer number differently and so pick a different survivor
   *representation* within a `(s, i)` group that differs only by such a value — a group
   already flagged as a conflict. This matters only once a second-language target exists.
4. **Sort** the survivors by key. After step 3 no two survivors share `(s, i)`, so no two
   have equal keys and the order is strict and total.

The result carries the ordered rows, the error rows, the collapsed count, the conflict
keys, and the count of rows read. `len(ordered) + len(errors) + collapsed == read` always.
Two inputs that are permutations of each other give equal results: the same ordered rows,
collapsed count and conflict keys, and the same error rows as a multiset (position
excluded).

Dedupe on the key `(source, id)` is the ADR's proposed key, not `id` alone: two events may
share an id across sources.

## Recommendation: the `source` tie-break

#6185 added `s` to the key and asked this repository to confirm it. **Recommendation:
keep `s` last in the key.** `(source, id)` is the identity of a CloudEvent, so a total
order over a log that mixes sources has to break the tie between two rows that agree on
everything else, and `s` is the one remaining attribute that identifies them. Last place
keeps the ruled order (Lamport, time, id) untouched and consults `s` only when every
earlier part is equal. `nodeid` need not join the key: `(nodeid, lamportclock)` is unique
per producer, and equal clocks on different nodes are separated by `time` and `id`.

One consequence the maintainer should know: a legacy row (no `source`) and the same event
re-emitted later as a sourced CloudEvent have different `(s, i)`, so they do not collapse.
Giving legacy rows their `source` is the upcaster's job (#92), after which they do.
Likewise a row first written under the project-scoped fallback source and later re-fired with a
mission source has two keys: a producer that wants one row per id dedupes on `id` alone within its
own log, and a reader that wants to notice the collision can compare ids across sources.

## Fixtures

`fixtures/total_order/` holds thirteen cases. Cases 1 to 9 are the worked examples of
#6185 (two clockless rows; two clocked rows; mixed; the non-transitive counter-example;
instants and ids; an error row and a duplicate; the source tie-break; two spellings of one
id; a conflict with every error class). Cases 10 to 13 pin the clock value types, the time
forms, the source forms and non-object lines. Each case lists labelled rows and the pinned
result. A comparator in any language passes if every permutation of every case gives that
result. Expected results were written by hand from the worked examples, not produced by
running the implementation.

## Versioning

Adding this contract and its implementation is additive (minor). Once a reducer or merge
driver sorts by it, changing the key is a change to every consumer's reduced state and is
a major change under [versioning-and-compatibility.md](./versioning-and-compatibility.md).
