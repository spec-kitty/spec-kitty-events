#!/usr/bin/env bash
# Print a JAVA_HOME with a JDK 25 or newer, for the Java binding (`make test-java`).
#
# Uses $JAVA_HOME, then `java` on PATH, when either is version 25+. Otherwise downloads the
# pinned Eclipse Temurin 25 release (the JDK core's acceptance JVM uses) into .cache/jdk-25,
# verifies its SHA-256 against the checksum pinned below before extracting it, and prints
# that home. Needs curl, tar and sha256sum (or shasum on macOS). The CI runner images are not this repository's to pin, so the
# build provisions its own JDK rather than assume one (research R-7, spec-kitty-events#92).
set -euo pipefail

want=25
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cache="$root/.cache/jdk-$want"

# The pinned Temurin release and the SHA-256 of each platform's archive (Adoptium's published
# checksums for jdk-25.0.5+8). Bump all of them together; a mismatch fails closed.
RELEASE="jdk-25.0.5+8"
LINUX_X64="OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz 68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb"
LINUX_AARCH64="OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.5_8.tar.gz bb5b2ded252f21c6090cf780b0a408ad0d83bbb557e702955643557f13231880"
MAC_X64="OpenJDK25U-jdk_x64_mac_hotspot_25.0.5_8.tar.gz 32cd4d2151c91aa00d0fc911edfd05245ed40fa6b1aa894d5f35c928cd67a59d"
MAC_AARCH64="OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5_8.tar.gz 36dc14d797aaff6d89aab302cd053fdba6207dfdcf772052fa7b023a94b0e857"

major_of() {  # the feature version of a `java` binary, or 0
  "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' | head -1 | cut -d. -f1 || echo 0
}

for candidate in "${JAVA_HOME:-}" "$(dirname "$(dirname "$(readlink -f "$(command -v java 2>/dev/null || echo /nonexistent)")")")"; do
  if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && [ "$(major_of "$candidate/bin/java")" -ge "$want" ] 2>/dev/null; then
    echo "$candidate"
    exit 0
  fi
done

if [ ! -x "$cache/bin/java" ]; then
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64 | Linux/amd64) asset="$LINUX_X64" ;;
    Linux/aarch64 | Linux/arm64) asset="$LINUX_AARCH64" ;;
    Darwin/x86_64) asset="$MAC_X64" ;;
    Darwin/arm64 | Darwin/aarch64) asset="$MAC_AARCH64" ;;
    *) echo "ensure_jdk25: no pinned Temurin build for $(uname -s)/$(uname -m)" >&2; exit 1 ;;
  esac
  name="${asset% *}"
  checksum="${asset#* }"
  url="https://github.com/adoptium/temurin25-binaries/releases/download/$RELEASE/$name"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  echo "ensure_jdk25: downloading $RELEASE ($name)" >&2
  curl -fsSL -o "$tmp/jdk.tar.gz" "$url"
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$tmp/jdk.tar.gz" | cut -d' ' -f1)"
  else  # macOS
    actual="$(shasum -a 256 "$tmp/jdk.tar.gz" | cut -d' ' -f1)"
  fi
  if [ "$actual" != "$checksum" ]; then
    echo "ensure_jdk25: checksum mismatch for $name ($actual, pinned $checksum)" >&2
    exit 1
  fi
  mkdir -p "$tmp/x"
  tar -xzf "$tmp/jdk.tar.gz" -C "$tmp/x"
  # Linux: <jdk>/bin/java; macOS: <jdk>/Contents/Home/bin/java
  home="$(find "$tmp/x" -maxdepth 5 -type f -path '*/bin/java' | head -1 | xargs dirname | xargs dirname)"
  rm -rf "$cache"
  mkdir -p "$(dirname "$cache")"
  mv "$home" "$cache"
fi
echo "$cache"
