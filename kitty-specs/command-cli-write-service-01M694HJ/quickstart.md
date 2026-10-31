# Quickstart: command-cli (PR1)

```bash
# build the jar (JDK 25)
jh="$(scripts/ensure_jdk25.sh)"
JAVA_HOME="$jh" ./gradlew --no-daemon :command-cli:bootJar

# a throwaway git common dir
tmp="$(mktemp -d)"; git -C "$tmp" init -q; gcd="$(git -C "$tmp" rev-parse --absolute-git-dir)"

# start it the way core's launcher does
java -jar service/command-cli/build/libs/command-cli.jar serve --git-common-dir "$gcd" --idle-timeout 600 \
  >>"$gcd/spec-kitty-write-service/service.log" 2>&1 &

# readiness, per contracts/mission-write/LAUNCHER.md
ep="$gcd/spec-kitty-write-service/endpoint.json"
port=$(python3 -c "import json;print(json.load(open('$ep'))['port'])")
tok=$(python3 -c "import json;print(json.load(open('$ep'))['token'])")
curl -s -H "Authorization: Bearer $tok" "http://127.0.0.1:$port/api/v1/handshake"
curl -s -H "Authorization: Bearer $tok" "http://127.0.0.1:$port/api/v1/health"

# a write refuses fail-closed (until #160)
curl -s -X POST -H "Authorization: Bearer $tok" -H "Spec-Kitty-Write-Protocol: 1" -H "Content-Type: application/json" \
  --data @contracts/mission-write/examples/… "http://127.0.0.1:$port/api/v1/missions/<ulid>/work-package-transitions"

# stop
curl -s -X POST -H "Authorization: Bearer $tok" -H "Spec-Kitty-Write-Protocol: 1" "http://127.0.0.1:$port/api/v1/shutdown"
```

Note: the core launcher client still calls bare `/handshake`. spec-kitty/spec-kitty#6910 moves it to `/api/v1`.
