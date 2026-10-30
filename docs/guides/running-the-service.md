---
title: Running the Mission Status Read API
description: 'How to run the Mission Status Read API from a service-v* release or from source: verify the download, start it, configure it, and which routes it serves.'
doc_status: draft
updated: 2026-10-29T00:00:00Z
type: Guide
kind: How-To
related:
  - guides/service-protocol-and-releases
  - guides/service-cutover-runbook
---

# Running the Mission Status Read API

> **Draft until cutover.** The service still lives in core (`spec-kitty/spec-kitty`,
> `services/mission-status-api/`) and moves to `service/` in this repository at cutover
> ([ADR-0002](../adr/0002-events-domain-owns-its-services.md)). No `service-v*` release
> exists yet. Every property and route below is quoted from core at `4eecb124`
> ([README](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/services/mission-status-api/README.md),
> [`application.properties`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/services/mission-status-api/src/main/resources/application.properties)).

The Mission Status Read API reads the Missions of one project directory and serves their
status over HTTP. It is read-only and loopback-only. What it is and why it is shaped this way
is in [the read-write split](../architecture/services/read-write-split.md); this page is how
to run it.

## Prerequisites

Pick one:

- **A released native binary** for your platform (linux-x64, linux-arm64, macos-arm64 or
  windows-x64). Nothing else is needed.
- **The released JVM jar**, with a Java 25 runtime.
- **The source** (after cutover), with JDK 25 and Python with this repository's locked
  environment (`uv sync --frozen`) to build the contract bundle. GraalVM CE 25 is needed
  only for a local native build. `scripts/ensure_jdk25.sh` finds a JDK 25 or provisions
  Temurin 25 into `.cache/`.

## Run a release

1. **Download** the asset for your platform and its checksum file from the
   `service-v<ver>` release. The names are fixed by
   [ADR-0003](../adr/0003-gradle-build-and-service-release-line.md):

   ```bash
   ver=0.1.0-rc.1        # the tag without the service-v prefix
   asset=mission-status-api-${ver}-linux-x64
   gh release download "service-v${ver}" --repo spec-kitty/spec-kitty-events \
     --pattern "$asset" --pattern "$asset.sha256"
   ```

   On Windows the binary is `mission-status-api-<ver>-windows-x64.exe`; the JVM fallback is
   `mission-status-api-<ver>.jar`.

2. **Verify** the bytes and their provenance:

   ```bash
   sha256sum -c "$asset.sha256"
   gh attestation verify "$asset" --repo spec-kitty/spec-kitty-events \
     --signer-workflow spec-kitty/spec-kitty-events/.github/workflows/service-release.yml \
     --source-ref "refs/tags/service-v$ver"
   ```

   Expected: `<asset>: OK`, then a verification that names this repository and the
   `service-release` workflow. The checksum shows the download matches what the build
   produced; the attestation shows where it was built. Stop if either fails.

3. **Start** it against a project. A release asset carries no file mode, so on Linux and
   macOS make it executable first. The binaries are unsigned, so macOS Gatekeeper and
   Windows SmartScreen warn on the first run.

   ```bash
   chmod +x "$asset"
   ./"$asset" --mission-status.project-root=/path/to/project --mission-status.port=8787
   # or the JVM fallback
   java -jar mission-status-api-${ver}.jar --mission-status.project-root=/path/to/project
   ```

4. **Check** it answers: `curl -s http://127.0.0.1:8787/api/v1/project`.

## Run from source (after cutover)

The service build serves the contract document it was given, checked against a digest, so
build the bundle once, outside the repository, and point Gradle at it. These are the steps
`service.yml`'s `contract-bundle` and `gradle` jobs run, and the ones the
[cutover runbook](./service-cutover-runbook.md#5-verification) runs before `verifyAll`:

```bash
export LC_ALL=C.UTF-8
out="$(mktemp -d)"
uv run --frozen python contracts/tools/bundle.py --root contracts --out "$out" --bundle-only
export CONTRACT_BUNDLE="$out/bundle/mission-status/openapi.yaml"
export CONTRACT_BUNDLE_SHA256="$(sha256sum "$CONTRACT_BUNDLE" | cut -d' ' -f1)"

./gradlew --no-daemon verifyAll                  # every module, the service's check included
./gradlew :mission-status-api:bootRun --args='--mission-status.project-root=/path/to/project'
./gradlew :mission-status-api:nativeCompile     # GraalVM CE 25; the binary is in service/build/native/nativeCompile/
```

`-PcontractBundle=<file> -PcontractBundleSha256=<digest>` work instead of the two variables.
Without either, `bundleContract` fails and names what is missing, and so does every task
that depends on it (`verifyAll` included). Without `--module`, `bundle.py` writes every
module (`bundle/mission-status`, `bundle/mission-write`); the service reads only
`mission-status`. Run the Gradle tests under a UTF-8 locale (`LC_ALL=C.UTF-8`).

## Configuration

The configuration keys (README, "How to run"):

| Key | Default | Meaning |
|---|---|---|
| `mission-status.project-root` | none, required | The project directory to read. The service refuses to start without it, or when it is not a directory. |
| `mission-status.port` | `8787` | The port; `0` asks the operating system for a free one. |
| `mission-status.stream.queue-capacity` | `1024` | The capacity of each stream subscriber's queue. |
| `mission-status.stream.heartbeat-interval` | `PT30S` | The interval of the idle-stream heartbeat comment (ISO-8601 duration). |
| `mission-status.stream.max-connections` | `128` | The most event streams open at once. Over it a request is answered `503` with `stream_capacity_reached`. |

Pass them on the command line (`--key=value`) or through the environment. What you cannot
change:

- **The bind address.** `server.address` is packaged as `127.0.0.1`. BindGuard refuses any
  other value, `localhost` included, and exits non-zero. A port already in use also exits
  non-zero; the service never falls back to another port or interface.
- **Where configuration files come from.** ConfigPin fixes them to the packaged
  `application.properties`. A `./application.properties`, `./config/application.properties`,
  `SPRING_CONFIG_LOCATION`, `SPRING_CONFIG_ADDITIONAL_LOCATION` or `spring.config.import` is
  not read, so a project cannot reconfigure a service started inside it.
- **Static serving.** `spring.web.resources.add-mappings` is `false`.

Reads are capped: `meta.json`, `lanes.json` and the two `.kittify` YAML files at 1 MiB, an
event log at 64 MiB. Size the heap (`-Xmx`) for the number of concurrent ledger reads you
allow, since the cap is per read.

## Routes

The service answers these routes, and every other path is `404`
(the contract is [`contracts/mission-status`](https://github.com/spec-kitty/spec-kitty/tree/4eecb12414dac53432f4af21ba958d199d5ce808/contracts/mission-status);
a running service serves it at `/api/v1/openapi.yaml`):

| Route | What it returns |
|---|---|
| `GET /api/v1/project` | The project header. |
| `GET /api/v1/missions` | A page of Mission overviews. `pageSize` defaults to 50 and is clamped to 1–200; a `pageCursor` the service did not issue is `400 invalid_page_cursor`. |
| `GET /api/v1/events` | The change stream (`text/event-stream`): `status-transition`, `mission-lifecycle` and `log-truncated`, with a `: heartbeat` comment while idle. Scope it with `missionId`; resume with `streamCursor` or `Last-Event-ID`. |
| `GET /api/v1/missions/{missionId}/artifacts` | The eligible files of one Mission's primary directory, at most 1000, with `truncated`. |
| `GET /api/v1/missions/{missionId}/artifacts/content?path=` | The decoded and redacted text of one artifact, or one of seven refusals (400, 404, 413, 415, 422, 500). |
| `GET /api/v1/openapi.yaml` | The contract document the service was built with. |

Every request passes the Host and Origin guard first: `Host` must be `127.0.0.1:<port>` or
`localhost:<port>`, an `Origin` must name the same, and `Sec-Fetch-Site: cross-site` is
refused, all with `403 request_refused`. The service never answers a fault as a healthy
success; core's README lists each fault (L1 to L11) with its status and diagnostic code, and
the exact paths each read opens (the read allowlist and the artifact read allowlist).

## Who runs it

- **External UIs.** The dashboard in `spec-kitty-mission-ui` and Kitty Desktop start and
  read it. No spec-kitty command starts it, and it is not in the PyPI wheel.
- **Core CI only.** Core pins a `service-v*` release by tag and sha256 for its own CI
  ([ADR-0002](../adr/0002-events-domain-owns-its-services.md), decision 3); see
  [service protocol and releases](./service-protocol-and-releases.md).
- **This repository's CI.** `service.yml` builds it, replays the golden requests against the
  native binary on four targets, and, nightly, compares it with core's Python reducer over
  core's corpus.
