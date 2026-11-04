// Stages the build inputs the docsite reads from outside docs/ (run as `predev` and `prebuild`).
//   1. $SKE_CONTRACT_BUNDLES_DIR/{mission-status,mission-write}/openapi.yaml -> site/openapi/
//      plus a sha256sum-format sidecar per file (read by the `openapi` option in astro.config.mjs).
//   2. the in-scope schemas -> site/schemas/  (the `kind: Schematic` pages' schematic_path):
//      src/spec_kitty_events/schemas/*.schema.json        -> site/schemas/<name>
//      producer_contract/data/*.schema.json               -> site/schemas/producer-rows/<name>
//      producer_contract/envelope{,.read}.schema.json     -> site/schemas/<name>
//      (Out-of-map on WP03: WP04 extends staging here so ONE script owns every staged
//      input and the npm prebuild stays a single call.)
//   3. assert parity, fail closed: the staged schema set must equal the set of
//      `schematic_path:` values across docs/events/**,
//      docs/architecture/producer-row-schemas/** and docs/api/envelope/** (FR-007's
//      build-time backstop; scripts/schema_pages.py --check is the source-of-truth drift
//      check). Offenders are named.
// The contract bundles are NEVER inside the repository: bundle.py refuses in-tree output
// (OUT_INSIDE_REPOSITORY), so the script reads SKE_CONTRACT_BUNDLES_DIR (default
// ${RUNNER_TEMP:-/tmp}/bundle-out/bundle) and is fail-closed: a missing bundle fails the
// build naming the bundle command that produces it. Both target directories are git-ignored.
// Node 22, plain ESM, no dependencies. Run from anywhere: the script resolves its own site/ root.
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';

import { basename, join } from 'node:path';
import { fileURLToPath } from 'node:url';

process.chdir(fileURLToPath(new URL('..', import.meta.url)));

const bundlesDir = process.env.SKE_CONTRACT_BUNDLES_DIR ?? join(process.env.RUNNER_TEMP ?? '/tmp', 'bundle-out', 'bundle');
const modules = ['mission-status', 'mission-write'];
const bundleRecipe =
  'export SKE_CONTRACT_BUNDLES_DIR="$(mktemp -d)/bundle-out/bundle"; ' +
  'uv run --frozen --no-sync python contracts/tools/bundle.py --root contracts ' +
  '--out "$(dirname "$SKE_CONTRACT_BUNDLES_DIR")" --bundle-only';

// 1. Fail closed on a missing bundle.
const problems = [];
for (const module of modules) {
  if (!existsSync(join(bundlesDir, module, 'openapi.yaml'))) {
    problems.push(`contract bundle missing: run '${bundleRecipe}'`);
  }
}

let staged = 0;
if (problems.length === 0) {
  // 2. Stage a clean copy of both bundles plus a sha256 sidecar (`<hex>  <name>`).
  rmSync('openapi', { recursive: true, force: true });
  mkdirSync('openapi', { recursive: true });
  for (const module of modules) {
    const name = `${module}.yaml`;
    const target = join('openapi', name);
    cpSync(join(bundlesDir, module, 'openapi.yaml'), target);
    const digest = createHash('sha256').update(readFileSync(target)).digest('hex');
    writeFileSync(`${target}.sha256`, `${digest}  ${basename(target)}\n`);
    staged += 1;
  }
}

// WP04 (#195): stage the schemas the 168 `kind: Schematic` pages read via schematic_path.
// Excluded by rule (FR-005 edge cases) and here by glob shape: producer_contract/composed/**,
// producer_contract/registry.json, producer_contract/fixtures/**, schemas/generate.py,
// schemas/__init__.py.
const schemasRoot = join('..', 'src', 'spec_kitty_events');
const stagedPaths = [];
rmSync('schemas', { recursive: true, force: true });
mkdirSync(join('schemas', 'producer-rows'), { recursive: true });
for (const f of readdirSync(join(schemasRoot, 'schemas')).filter((f) => f.endsWith('.schema.json')).sort()) {
  cpSync(join(schemasRoot, 'schemas', f), join('schemas', f));
  stagedPaths.push(`schemas/${f}`);
}
for (const f of readdirSync(join(schemasRoot, 'producer_contract', 'data')).filter((f) => f.endsWith('.schema.json')).sort()) {
  cpSync(join(schemasRoot, 'producer_contract', 'data', f), join('schemas', 'producer-rows', f));
  stagedPaths.push(`schemas/producer-rows/${f}`);
}
for (const f of ['envelope.schema.json', 'envelope.read.schema.json']) {
  cpSync(join(schemasRoot, 'producer_contract', f), join('schemas', f));
  stagedPaths.push(`schemas/${f}`);
}
stagedPaths.sort();

// 3. Parity (fail closed): the staged set must equal the set of `schematic_path:` values
// found across the schematic-page trees. A staged schema without a page (or a page naming
// an unstaged path) breaks the build naming the offenders.
const pageTrees = [
  join('..', 'docs', 'events'),
  join('..', 'docs', 'architecture', 'producer-row-schemas'),
  join('..', 'docs', 'api', 'envelope'),
];
const namedPaths = new Set();
function collect(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'README.md') continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      collect(full);
      continue;
    }
    if (!entry.name.endsWith('.md')) continue;
    const front = /^---\r?\n([\s\S]*?)\r?\n---/.exec(readFileSync(full, 'utf8'))?.[1] ?? '';
    const path = /^schematic_path:\s*["']?([^"'\r\n]+?)["']?\s*$/m.exec(front)?.[1];
    if (path) namedPaths.add(path);
  }
}
for (const tree of pageTrees) collect(tree);

for (const p of stagedPaths) {
  if (!namedPaths.has(p)) problems.push(`staged schema has no page: ${p}`);
}
for (const p of [...namedPaths].sort()) {
  if (!stagedPaths.includes(p)) problems.push(`page's schematic_path is not a staged schema: ${p}`);
}

if (problems.length) {
  for (const p of problems) console.error(`stage-docs-inputs: ${p}`);
  process.exit(1);
}
console.log(`stage-docs-inputs: ${staged} contract bundles, ${stagedPaths.length} schemas staged (${namedPaths.size} schematic pages in parity)`);
