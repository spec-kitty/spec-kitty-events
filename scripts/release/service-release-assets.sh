#!/usr/bin/env bash
# Assembles the service-v* release assets (ADR 0003, "Release assets"; the asset table there is the
# contract, and tests/test_service_workflows.py checks this script against it).
#
# Usage:
#   scripts/release/service-release-assets.sh <tag> <outdir> <native-artifacts-dir>
#   scripts/release/service-release-assets.sh --list-assets <ver>
#
# <tag>                   service-vX.Y.Z[-pre]; <ver> is the tag without the service-v prefix.
# <outdir>                created if absent; it must be empty.
# <native-artifacts-dir>  holds the four natives (mission-status-api-<ver>-<target>[.exe]) and the
#                         JVM fallback mission-status-api-<ver>.jar, as service.yml uploads them.
#
# Environment:
#   BUNDLE_OUT         required: the --out directory of `contracts/tools/bundle.py --bundle-only`;
#                      its bundle/ tree becomes contract-bundles-<ver>.tar.gz.
#   REPO_ROOT          the checkout holding contracts/tools/ and service/ (default: the git top level).
#   SOURCE_DATE_EPOCH  the tarball mtime (default: the commit time of HEAD in REPO_ROOT).
#
# The tarballs are deterministic (sorted names, owner 0, fixed mode and mtime, gzip -n), so running
# this twice on the same commit gives the same sha256 for every asset. It publishes nothing.
set -euo pipefail

TAG_RE='^service-v([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?)$'
TARGETS=(linux-x64 linux-arm64 macos-arm64 windows-x64)

die() {
  echo "service-release-assets: $*" >&2
  exit 1
}

# The release assets for <ver>, without the checksum files. Keep in step with the ADR 0003 table.
assets() {
  local ver="$1" target
  for target in "${TARGETS[@]}"; do
    if [ "$target" = windows-x64 ]; then
      echo "mission-status-api-${ver}-${target}.exe"
    else
      echo "mission-status-api-${ver}-${target}"
    fi
  done
  echo "mission-status-api-${ver}.jar"
  echo "contract-bundles-${ver}.tar.gz"
  echo "contract-tools-${ver}.tar.gz"
  echo "conformance-fixtures-${ver}.tar.gz"
}

if [ "${1:-}" = "--list-assets" ]; then
  [ $# -eq 2 ] || die "usage: $0 --list-assets <ver>"
  assets "$2"
  exit 0
fi

[ $# -eq 3 ] || die "usage: $0 <tag> <outdir> <native-artifacts-dir>"
tag="$1"
outdir="$2"
natives="$3"

[[ "$tag" =~ $TAG_RE ]] || die "'$tag' is not a service release tag (service-vX.Y.Z[-pre])"
ver="${BASH_REMATCH[1]}"

: "${BUNDLE_OUT:?BUNDLE_OUT must name the --out directory of contracts/tools/bundle.py --bundle-only}"
[ -d "$BUNDLE_OUT/bundle" ] || die "no bundle/ tree under BUNDLE_OUT=$BUNDLE_OUT"
[ -d "$natives" ] || die "no native artifacts directory: $natives"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel)}"
[ -d "$REPO_ROOT/contracts/tools" ] || die "no contracts/tools/ under $REPO_ROOT"
[ -d "$REPO_ROOT/service" ] || die "no service/ under $REPO_ROOT: service-v* releases need the cutover import"
if [ -z "${SOURCE_DATE_EPOCH:-}" ]; then
  SOURCE_DATE_EPOCH="$(git -C "$REPO_ROOT" log -1 --format=%ct)"
fi
export SOURCE_DATE_EPOCH

mkdir -p "$outdir"
outdir="$(cd "$outdir" && pwd)"
if [ -n "$(ls -A "$outdir")" ]; then
  die "$outdir is not empty"
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Deterministic tar.gz of the given paths, relative to <dir>.
det_tar() {
  local out="$1" dir="$2"
  shift 2
  LC_ALL=C tar --sort=name --format=gnu --owner=0 --group=0 --numeric-owner \
    --mode='u+rwX,go+rX,go-w' --mtime="@${SOURCE_DATE_EPOCH}" \
    -C "$dir" -cf - "$@" | gzip -n -9 > "$out"
}

# Copies <src> (relative to <from>) into <to>/<dest>, creating parent directories.
stage() {
  local from="$1" src="$2" to="$3" dest="$4"
  [ -e "$from/$src" ] || die "missing $from/$src"
  mkdir -p "$(dirname "$to/$dest")"
  cp -R "$from/$src" "$to/$dest"
}

# 1. The natives and the JVM fallback jar.
for name in $(assets "$ver" | grep '^mission-status-api-'); do
  [ -f "$natives/$name" ] || die "missing $natives/$name"
  cp "$natives/$name" "$outdir/$name"
done

# 2. contract-bundles: the bundle/ tree, as bundle.py wrote it.
det_tar "$outdir/contract-bundles-${ver}.tar.gz" "$BUNDLE_OUT" bundle

# 3. contract-tools: the tool scripts and the two pin files, no test fixtures.
tools="$work/tools"
mkdir -p "$tools"
for f in "$REPO_ROOT"/contracts/tools/*.py; do
  stage "$REPO_ROOT" "contracts/tools/$(basename "$f")" "$tools" "contracts/tools/$(basename "$f")"
done
stage "$REPO_ROOT" contracts/tools/pins.json "$tools" contracts/tools/pins.json
stage "$REPO_ROOT" contracts/tools/enum_pins.json "$tools" contracts/tools/enum_pins.json
det_tar "$outdir/contract-tools-${ver}.tar.gz" "$tools" contracts

# 4. conformance-fixtures: entry names are the contract's service-relative names; each is staged
#    from where the service build keeps it.
fixtures="$work/fixtures"
mkdir -p "$fixtures"
svc="$REPO_ROOT/service"
stage "$svc" src/test/resources/conformance "$fixtures" conformance
stage "$svc" src/test/resources/artifact-conformance/cases.json "$fixtures" artifact-conformance/cases.json
stage "$svc" src/test/resources/artifact-conformance/golden.json "$fixtures" artifact-conformance/golden.json
stage "$svc" src/test/resources/nativesmoke/requests.json "$fixtures" nativesmoke/requests.json
stage "$svc" src/main/resources/leak-patterns.json "$fixtures" leak-patterns.json
stage "$svc" event-log/src/test/resources/torn-tail/repair-cases.json "$fixtures" event-log/torn-tail/repair-cases.json
det_tar "$outdir/conformance-fixtures-${ver}.tar.gz" "$fixtures" \
  conformance artifact-conformance nativesmoke leak-patterns.json event-log

# 5. One .sha256 per asset and SHA256SUMS, sorted by name.
(
  cd "$outdir"
  for name in $(assets "$ver"); do
    sha256sum "$name" > "$name.sha256"
  done
  assets "$ver" | LC_ALL=C sort | xargs sha256sum > SHA256SUMS
)

# 6. The set must equal the contract exactly: nothing missing, nothing extra.
expected="$work/expected"
actual="$work/actual"
{
  assets "$ver"
  assets "$ver" | sed 's/$/.sha256/'
  echo SHA256SUMS
} | LC_ALL=C sort > "$expected"
(cd "$outdir" && find . -mindepth 1 -maxdepth 1 -printf '%f\n') | LC_ALL=C sort > "$actual"
if ! diff -u "$expected" "$actual"; then
  die "the asset set in $outdir differs from the contract (ADR 0003)"
fi
(cd "$outdir" && sha256sum --check --strict --quiet SHA256SUMS)
echo "service-release-assets: $(wc -l < "$expected" | tr -d ' ') files for $tag in $outdir"
