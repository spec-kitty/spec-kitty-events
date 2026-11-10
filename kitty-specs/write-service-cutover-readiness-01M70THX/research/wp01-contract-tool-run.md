# WP01 contract tool run

Run on 2026-11-09 from the repository root on branch `wp01-contract`.
The real events baseline was `origin/main` at
`62af21d01bd996d0231a983f35bcaa786a66218a`. The throwaway core worktree for
citations was `origin/main` at `feb623c31f269d348554075a349337298c2ff53e`;
it was removed after the check. All generated tools, baselines and bundles were
outside the repository. Exit status was 0 for every command below except the
first `breaking_check.py` run. Its exit 1 and two false findings came from stripping a provisional
schema, and ruling D11 fixed the tool: see "D11 re-run" below. The final run exits 0 with
`breaking=0`.

## Install the pinned tools

```bash
run_dir=$(mktemp -d $TMPDIR/wp01-contract-run.XXXXXX)
uv run python contracts/tools/install_tools.py --pins contracts/tools/pins.json --dest "$run_dir/tools"
```

The actual run directory was `$RUN`. Output:

```text
installed gradle 9.8.1 sha256=dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f
installed vacuum 0.30.6 sha256=179f038a71cd88721738d6c739dd07fbeebc905b48301935f2416e66aead95c6
installed oasdiff 1.32.1 sha256=7c8939fc49b75ee11fec66a5b83b37a2fca6aee109fed85013b1ba2ac2a1ee7f
bin directory: $RUN/tools/gradle-9.8.1/bin
bin directory: $RUN/tools/vacuum-0.30.6
bin directory: $RUN/tools/oasdiff-1.32.1
counts: tools_installed=3
```

## Real-baseline breaking check

```bash
B=$(mktemp -d $TMPDIR/wp01-baseline.XXXXXX)
git archive origin/main contracts | tar -x -C "$B"
PATH="$RUN/tools/oasdiff-1.32.1:$PATH" \
  uv run python contracts/tools/breaking_check.py \
  --root contracts --module mission-write --baseline-root "$B/contracts"
```

Complete output from the final candidate (exit 1, expected):

```text
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: GET /handshake: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `401`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: GET /handshake: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `403`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: GET /health: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `401`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: GET /health: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `403`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `400`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `401`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `403`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `404`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `409`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `413`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `422`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `500`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `503`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `400`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `401`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `403`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `404`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `409`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `413`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `422`
CONTRACT-CHECK breaking_check: BREAKING_WITHOUT_MAJOR: mission-write: POST /missions/{missionId}/work-package-transitions: removed the request property `operation` (baseline baseline-root 1.0.1-SNAPSHOT, candidate 1.0.1-SNAPSHOT)
CONTRACT-CHECK breaking_check: BREAKING_WITHOUT_MAJOR: mission-write: POST /missions/{missionId}/work-package-transitions/batch: removed the request property `units/items/operation` (baseline baseline-root 1.0.1-SNAPSHOT, candidate 1.0.1-SNAPSHOT)
counts: modules=1 baselines=1 breaking=2 provisional_changes=34 no_baseline_initial=0 preview_ref=skipped
```

`baselines=1` proves this was not a vacuous no-baseline run. The only two
stable findings are the expected removal of `TransitionUnit.operation` after
provisional stripping, once in the single-unit request and once in the batch.
There is no `BUNDLE_CHANGED_VERSION_SAME` line because `breaking_check.py`
returns from its comparison after reporting stable breaking entries, before its
version-same branch. The `PROVISIONAL_CHANGE` notes come from the requested
wording edits inside the already-provisional `WriteRefusalReason` and do not fail
the check.

**WP01 review correction (orchestrator).** The read-back wording sits on the `REQUEST_MALFORMED`
const description itself, as T004 asks. An earlier draft moved it to the schema-level description,
believing the const edit caused oasdiff's `response ... oneOf list ... added` notes. That does not
reproduce. The reviewer's control run shows that all 34 `PROVISIONAL_CHANGE` notes, which are
"response-*-added" entries on existing paths, come from the description-only edits on the
provisional `WriteRefusalReason`: restoring the baseline `WriteRefusalReason.yaml` drops them to 0.
They are accepted only for that reason, and they never fail the check.

## D11 re-run (breaking_check, 2026-11-10)

The block above is the run that exposed the false break. Maintainer ruling D11 (2026-11-10) fixed the
tool, not the contract: `WriteOperation` stays `x-provisional`, and `breaking_check.py` no longer deletes a
stable property that only references (inlines) a provisional schema. Such a property keeps its presence
and its requiredness under comparison; only the provisional schema's own type and enum are replaced by
the same neutral stub in both bundles. A property that is itself provisional is still dropped.

```bash
B=$(mktemp -d $TMPDIR/d11-baseline.XXXXXX)
git archive origin/main contracts | tar -x -C "$B"
PATH="$RUN/tools/oasdiff-1.32.1:$PATH" \
  uv run python contracts/tools/breaking_check.py \
  --root contracts --module mission-write --baseline-root "$B/contracts"
```

Complete output from the same candidate (exit 1, see below; every `PROVISIONAL_CHANGE` note is the
`WriteRefusalReason` wording edit explained above):

```text
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: GET /handshake: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `401`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: GET /handshake: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `403`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: GET /health: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `401`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: GET /health: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `403`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `400`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `401`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `403`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `404`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `409`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `413`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `422`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `500`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/inner-state-annotations: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `503`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `400`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `401`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `403`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `404`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `409`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `413`
CONTRACT-CHECK breaking_check: PROVISIONAL_CHANGE: mission-write: POST /missions/{missionId}/work-package-transitions: added `subschema #6, subschema #7` to the `allOf[subschema #45]/type` response property `oneOf` list for the response status `422`
CONTRACT-CHECK breaking_check: BUNDLE_CHANGED_VERSION_SAME: mission-write: the bundle changed but info.version is 1.0.1-SNAPSHOT (baseline baseline-root 1.0.1-SNAPSHOT); move the version and add a CHANGELOG entry
counts: modules=1 baselines=1 breaking=0 provisional_changes=34 no_baseline_initial=0 preview_ref=skipped
```

`breaking=0`: both `removed the request property ... operation` findings are gone, and no other
`BREAKING_WITHOUT_MAJOR` appears. That intermediate run still exited 1 with `BUNDLE_CHANGED_VERSION_SAME`,
which the short-circuit on the false breaks used to hide. The baseline here is `main`'s tree, which carries
the same unreleased `1.0.1-SNAPSHOT`, and a snapshot is work in progress, never a release baseline (the
tool's own rule for tag baselines). The orchestrator therefore completed D11 by applying that rule to a
`--baseline-root` too. A change inside the same unreleased snapshot is not reported, and a breaking change
against it still is (negative case `breaking-snapshot-baseline-same-version`). The final run:

```text
counts: modules=1 baselines=1 breaking=0 provisional_changes=34 no_baseline_initial=0 preview_ref=skipped
```

It exits **0** with no breaks, so #236 AC 6 is met literally.

Tests added to `contracts/tools/negative_cases.json` (fixtures under
`contracts/tools/fixtures/breaking_check/baseline_ref` and `candidates/ref_*`), red against the previous
`breaking_check.py` (4 of 4 failed) and green after the change (4 of 4 pass):

- `breaking-provisional-ref-property-kept`: a stable property referencing a schema that became provisional
  is kept, exit 0.
- `breaking-provisional-ref-enum-not-reported`: changing that schema's enum is not reported, exit 0.
- `breaking-provisional-ref-property-removed`: removing the property is `BREAKING_WITHOUT_MAJOR`.
- `breaking-provisional-ref-property-unrequired`: making it optional is `BREAKING_WITHOUT_MAJOR`.

## Core citations

```bash
core_tmp=$(mktemp -d $TMPDIR/wp01-core.XXXXXX)
git -C ~/Documents/code/kitty/spec-kitty worktree add --quiet "$core_tmp" origin/main
git -C "$core_tmp" rev-parse HEAD
uv run python contracts/tools/citation_check.py \
  --root contracts --module mission-write --repo-root "$core_tmp"
git -C ~/Documents/code/kitty/spec-kitty worktree remove "$core_tmp"
```

Output:

```text
feb623c31f269d348554075a349337298c2ff53e
counts: properties=36 x_source=21 x_derived=15 inputs_resolved=19
```

## Layout, structure, provisional, enum and examples

The flags were checked with each tool's `--help` before the run.

```bash
for t in layout_check structure_check provisional_check enum_pin_check example_check; do
  uv run python "contracts/tools/$t.py" --root contracts
done
```

Output, in the same order (all exit 0):

```text
counts: modules=2 path_files=17 index_files=10
counts: readme_headings=35 changelog_headings=19
counts: provisional_elements=38
counts: enums=24 values=82
counts: examples=144 validated=144
```

## Bundle and vacuum lint

Help was checked first; its last five lines were:

```bash
uv run python contracts/tools/bundle.py --help
```

```text
  --module MODULE       restrict to this module (repeatable)
  --min-paths MIN_PATHS
  --write-verification-metadata
  --bundle-only         write and check the bundle only: no JVM, no Gradle
  --verbose             print the full Gradle output of every module
```

The bundle-only leg matches `service.yml`'s contract-bundle job.

```bash
uv run python contracts/tools/bundle.py --root contracts \
  --out $RUN/final-bundle-out --bundle-only
PATH="$RUN/tools/vacuum-0.30.6:$PATH" \
  uv run python contracts/tools/lint_check.py \
  --bundles $RUN/final-bundle-out
```

Output (both exit 0):

```text
bundle sha256 mission-status 6deab07858e3a90e8570bec48ca36f7b37a95837e8bacdd7c345e19c73643f99
bundle sha256 mission-write 51cc53ab25bef817b126be2d0bfcc240f4f0d497e6f110690f4e0b1b85e98f53
counts: modules=2 bundles=2 path_items=17
counts: files=2 rules=9 violations=0
```

The full bundle leg also completed the split-root, bundle and Java-view checks:

```bash
jh="$(scripts/ensure_jdk25.sh)"
JAVA_HOME="$jh" \
PATH="$jh/bin:$RUN/tools/gradle-9.8.1/bin:$PATH" \
  uv run python contracts/tools/bundle.py --root contracts \
  --out $RUN/final-bundle-full
```

Output (exit 0):

```text
bundle sha256 mission-status 6deab07858e3a90e8570bec48ca36f7b37a95837e8bacdd7c345e19c73643f99
bundle sha256 mission-write 51cc53ab25bef817b126be2d0bfcc240f4f0d497e6f110690f4e0b1b85e98f53
counts: modules=2 bundles=2 path_items=17
```

The TypeScript consumer smoke also ran (its `--help` supplied `--out`):

```bash
jh="$(scripts/ensure_jdk25.sh)"
JAVA_HOME="$jh" \
PATH="$jh/bin:$RUN/tools/gradle-9.8.1/bin:$PATH" \
  uv run python contracts/tools/client_smoke.py --root contracts \
  --out $RUN/final-client-smoke
```

Output (exit 0):

```text
counts: modules=2 files_emitted=166 generator_warnings=0
```

## Gradle contracts check

```bash
jh="$(scripts/ensure_jdk25.sh)" && JAVA_HOME="$jh" PATH="$jh/bin:$PATH" \
  ./gradlew --no-daemon -Dorg.gradle.java.installations.paths="$jh" :contracts:check
```

Last lines (exit 0):

```text
> Task :contracts:validate_mission-write
Validating spec <checkout>/contracts/mission-write/openapi.yaml
Spec is valid.

> Task :contracts:validate_mission-status
Validating spec <checkout>/contracts/mission-status/openapi.yaml
Spec is valid.

> Task :contracts:check

BUILD SUCCESSFUL in 3s
2 actionable tasks: 2 executed
Configuration cache entry reused.
```

## Repository blast radius

```bash
make test-fast
```

```text
4619 passed, 222 skipped in 21.96s
```

```bash
uv run pytest --pyargs spec_kitty_events.conformance
```

```text
1233 passed in 3.61s
```

```bash
make lint
```

```text
uv run ruff check .
All checks passed!
uv run ruff format --check .
1165 files already formatted
uv run python scripts/check_version_not_amended.py
version-not-amended: ok
uv run python scripts/changelog.py check
uv run python scripts/changelog.py guard
uv run python scripts/docs_index.py --check
uv run python scripts/schema_pages.py --check
schema_pages: checked; 0 file(s) clean
```

Finally, `git diff --check` exited 0 with no output.
