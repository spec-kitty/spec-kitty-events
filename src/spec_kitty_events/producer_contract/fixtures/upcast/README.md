# Upcast corpus

The fixture set every binding of the legacy-row upcaster runs (spec-kitty-events#92). The
contract is section 9 of `docs/architecture/cloudevents-envelope.md`.

Each `cases/<group>/<case>.json` holds:

| Key | Meaning |
|---|---|
| `line` | One log line as written (the bytes are its UTF-8 encoding). |
| `context` | The caller context: any of `missionid`, `missionslug`, `projectuuid`. |
| `expected.outcome` | `mapped` or `passthrough`. |
| `expected.form` | The detected form (`transport_envelope`, `flat`, `lifecycle_envelope`, `decision_envelope`, `journal_envelope`, `cloudevent`, `none`). |
| `expected.codes` | The issue codes, in order (empty when mapped). Strict use raises the first one, except `ALREADY_CLOUDEVENT`. |
| `expected.golden` | The canonical text of the mapped CloudEvent (sorted keys, no whitespace, UTF-8, Python `repr` floats), or `null`. |
| `expected.valid` | Whether the golden is a valid read-profile row of its type, or `null`. |
| `origin`, `notes` | Where the line comes from, and why the case exists. |

Groups: `transport` (one 3.0.0 envelope per registered transport type), `conformance` (every
3.0.0 envelope in the packaged conformance fixtures), `diary` (every status-diary replay line,
with a Mission id in context), `cli` (every producer fixture's legacy row) and `edge`.

`scripts/generate_upcast_corpus.py` writes the corpus from those sources and the edge cases it
states; `--check` fails on drift. Expected values are what the Python upcaster returns and are
reviewed as a diff, never regenerated blind. A binding passes when every case gives the same
outcome, form, codes and golden bytes, the same validity verdict, and a golden read again is a
pass-through with the same bytes.
