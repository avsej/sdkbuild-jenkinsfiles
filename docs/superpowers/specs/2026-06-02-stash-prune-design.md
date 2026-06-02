# Prune build workspace before stash

Date: 2026-06-02
Pipeline: `cxx/cxx-scripted-build-pipeline.groovy`
Status: approved (option A — prune-before-stash)

## Problem

`stash(includes: "ws_${platform}/", name: "${platform}_build")` ships the
entire per-platform workspace — measured at **7.7 GB** for a rocky9 Debug
build — to the Jenkins controller. Observed stash time: ~8 minutes per
platform. All seven platforms stash; integration tests currently unstash
only `rocky9-amd64`, but the other platforms' stashes are kept deliberately
(future plan: run integration on all platforms against a current-server
cbdinocluster or gocaves mock), so the fix is to slim universally, not to
skip stashing.

## What the consumer needs (measured, not guessed)

`bin/run-integration-tests` does **no compilation**: it runs
`ctest --label-regex integration` inside `cmake-build-tests/`. Verified
against a real build of the build-11 tarball (same `bin/build-tests`
invocation as CI):

- ctest traversal: root `CTestTestfile.cmake` contains
  `subdirs("_deps/boringssl-build")` etc. — the `CTestTestfile.cmake`
  chain must survive **including inside `_deps/`**.
- Test binaries link `libcouchbase_cxx_client.so.*` dynamically (ldd) —
  the shared libs at the build root must survive.
- `ctest -L integration` includes **example binaries** ("example: using
  fork() for scaling" et al.) — `examples/` executables must survive.
  A tests-only whitelist would have broken at runtime.
- Suppressions files / `bin/` scripts / `cluster.crt` handling live at
  PROJECT_ROOT — the source tree stays (it is small: the bulk is build
  output).

## Deletion list (rehearsed locally, 7741 MB → 2584 MB, −67%)

Inside `ws_${platform}/couchbase-cxx-client/<build dir>`:

1. every `CMakeFiles/` directory (object files; ~3.1 GB)
2. every `*.a` static archive (already linked into binaries; ~1.3 GB —
   `libcouchbase_cxx_client_static_intermediate.a` alone is 1.3 GB)
3. `_deps/` contents **except** `CTestTestfile.cmake` files, then empty
   dirs (~0.6 GB)

Verification on the pruned tree: `ctest --show-only` enumerates the same
176 integration / 438 total tests; `ldd` resolves on all test binaries;
`ctest -L unit` → 150/150 pass.

Explicitly kept (all load-bearing): ~50 Debug test executables (~40 MB
each), `libcouchbase_cxx_client.so*`, `tools/cbc`, `examples/`
executables, ctest metadata, `bin/`, suppressions, source tree.

Out of scope: stripping/compressing debug info (the integration runner
sets `ulimit -c unlimited` + apport on purpose — core-dump debuggability
beats further size cuts). Revisit `objcopy --compress-debug-sections` if
2.5 GB is still too slow.

## Design

New groovy helper `pruneForStash()` called in the build-matrix node right
before the `stash` step (the node's last act before releasing the
workspace — mutation is safe). Two branches:

- **Unix `sh`** (POSIX; dash/ash/BSD-find compatible — no GNU-only
  `-delete`/`-empty`, use `-exec rm/rmdir` instead): operates on
  `ws_${platform}/couchbase-cxx-client/cmake-build-tests`, prints
  before/after MB via `du -sm` so every build logs its savings.
- **powershell** for win2022-amd64: same three rules against the `build/`
  directory (`CMakeFiles` dirs, `*.lib`/`*.obj`, `_deps` minus
  `CTestTestfile.cmake`).

Failure policy: loud (`set -eu` / `$ErrorActionPreference = 'Stop'`).
The operations are mechanical deletions; if they fail, something is wrong
with the workspace and the 8-minute fallback stash should not paper over
it silently.

## Expected effect

Stash payload ~3× smaller before compression; tar/upload time should drop
proportionally (verify against the next build's timestamps). Same
reduction on the unstash side for every integration branch.
