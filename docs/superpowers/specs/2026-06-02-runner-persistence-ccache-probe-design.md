# Runner persistence / ccache probe

Date: 2026-06-02
Pipeline: `cxx/cxx-scripted-build-pipeline.groovy`
Status: approved (probe-only first step)

## Problem

Builds of the cxx-client tarball recompile grpc/BoringSSL/opentelemetry from
scratch on every run (~18 min rocky9 cold, worse on small ARM agents). ccache
is already fully wired in — `cmake/Cache.cmake` auto-detects it and
`bin/build-tests` defaults `CB_CACHE_OPTION=ccache` — so compile caching
already happens *within* a build. Whether it helps *across* builds depends
entirely on whether the agent (and its `$HOME`) survives between builds,
which we currently do not know. The IT department may redeploy agents at any
time; EC2 instances were observed changing between consecutive builds
(i-0000b89… in #8, i-0b7603… in #9).

## Decision

First step is measurement only: add a diagnostic probe that reveals, per
agent and per build, (a) how long the machine/container has been up, (b) how
many cxx-SDK builds it has executed so far, and (c) the size and hit-rate
history of any existing ccache. No `CCACHE_DIR` changes, no cache tuning,
nothing load-bearing.

## What the probe must distinguish

| Scenario | Signal | Speedup path it unlocks |
|---|---|---|
| Machine persistent, `$HOME` persists | uptime days+, marker count grows, ccache size grows | None needed — possibly raise `max_size` |
| Machine persistent, `$HOME`/workspace wiped | uptime days+, marker count stuck at 1 | Point `CCACHE_DIR` at a survivable path (docker volume, `/var/cache`) |
| Ephemeral agent (fresh EC2/container per build) | pid1 age ≈ minutes, hostname changes, markers always 1 | ccache 4.4+ remote storage (S3/HTTP), warm cache in AMI/image, or accept cold builds |

Container subtlety: on the docker-swarm agents `/proc/uptime` is the *host's*
uptime. Two complementary probes resolve this:

- `/proc/uptime` → host uptime.
- `stat -c %Y /proc/1` → PID 1 start ≈ container start (≈ boot time on a
  bare host). `pid1_age ≪ host_uptime` ⇒ containerized runner on a long-lived
  host ⇒ volume-mount `CCACHE_DIR` is the natural fix.

The build-marker count is the authoritative persistence signal; uptimes are
supporting evidence.

## Probe specification

New groovy helper `reportPersistence()`, sh-only (no powershell variant —
win2022-amd64 compiles with MSVC, no ccache; revisit if Windows wall-clock
becomes the bottleneck). Called at the three existing Unix diagnostic sites:

1. `environment` stage (prepare-and-validate, rocky docker agent),
2. per-platform matrix `prep` stage,
3. integration-test bring-up (docker + capella branches).

Constraints (lessons from builds #8/#9 on this branch):

- POSIX sh only — `/bin/sh` is dash on qe-ubuntu24-*, busybox ash on alpine.
  No `pipefail`, no bashisms.
- Diagnostic only — entire body under `set +e`, ends `exit 0`, mirrors the
  existing OS/toolchain dump heredoc. Missing tools (ccache, /proc on macOS)
  print nothing or a soft note, never fail.
- `stat -c` is GNU/busybox; macOS lacks it and `/proc` — guard with
  `[ -d /proc/1 ]` and `2>/dev/null`.

Body (shell sketch):

```sh
set +e
echo "===== Runner persistence / ccache ====="
uptime
[ -f /proc/uptime ] && awk '{printf "host_uptime_seconds: %d\n", $1}' /proc/uptime
if [ -d /proc/1 ]; then
    container_start=$(stat -c %Y /proc/1 2>/dev/null)
    if [ -n "$container_start" ]; then
        now=$(date +%s)
        echo "pid1_age_seconds: $((now - container_start)) (container/runner start)"
    fi
fi

markers="${XDG_CACHE_HOME:-$HOME/.cache}/cb-sdk-build-markers/${JOB_NAME:-unknown-job}"
mkdir -p "$markers"
touch "$markers/${BUILD_NUMBER:-0}.$(date +%s)"
echo "builds previously seen on this runner: $(ls "$markers" | wc -l) (incl. this one)"
ls "$markers" | sort | tail -5

ccache --version 2>/dev/null | head -1
ccache -s 2>/dev/null
cache_dir=$(ccache --get-config cache_dir 2>/dev/null || ccache -k cache_dir 2>/dev/null)
[ -n "$cache_dir" ] && du -sh "$cache_dir" 2>/dev/null
exit 0
```

Design points:

- Marker name `<BUILD_NUMBER>.<epoch>`: count answers "how many builds",
  names answer "which builds, when"; re-runs of a build number stay distinct.
- Marker dir under `XDG_CACHE_HOME` deliberately shares a survival domain
  with ccache's default dir (`~/.ccache` / `~/.cache/ccache`): markers
  surviving ⇒ the cache would have survived too.
- `ccache --get-config cache_dir` (4.x) with `ccache -k cache_dir` (3.x)
  fallback.
- `ccache -s` *before* the build doubles as hit-rate history on persistent
  runners.

## Interpretation / next step

Collect a handful of builds, read the probe output per agent pool, pick the
speedup path from the table above. Tuning (CCACHE_DIR relocation, remote
storage, max_size) is explicitly out of scope for this change.
