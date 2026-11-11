# Write-fold differential cases

Each `<id>.json` is a hand-written status log with the lane core's reducer reaches for it. The
package's `spec_kitty_events.diary.reduce` is the reducer core's `status/reducer.py` imports, so
`expected` is core's answer; where the reducer refuses a log, `expected` is
`{"unreducible": "<exception type>"}` and the write fold must refuse it too.
`FoldDifferentialTest` folds every file and compares. Regenerate with
`uv run python scripts/conformance/generate_fold_differential.py` and verify with `--check`
(`tests/test_fold_differential.py` runs the same check in the Python suite). Edit the cases in the
script, never the JSON.
