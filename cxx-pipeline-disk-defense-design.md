# Disk-pressure defense for `cxx-scripted-build-pipeline.groovy`

**Date:** 2026-05-19
**Status:** Approved design, ready for implementation
**Related:** [`jenkins-disk-defense.md`](./jenkins-disk-defense.md) (operational runbook this design implements)

## Context

Build `cxx-scripted-build-pipeline` #5224 failed on `build-window-sdk-04` with
`LNK1180: insufficient disk space to complete link` after the cxx-client added
gRPC-from-source dependencies (CXXCBC-777 / CXXCBC-778). The accompanying
runbook `jenkins-disk-defense.md` lays out a six-tier mitigation; this design
covers the pipeline-side portions (tiers §1, §4, §6) plus extensions the
runbook didn't address — coverage across all build platforms, not just
Windows, and across integration-test nodes that pull multi-GB Couchbase Docker
images.

Tiers §3 (node monitors) and §5 (nightly sweep job) live outside this
Jenkinsfile and are not in scope.

## Goals

1. **Catch disk-exhausted agents before the heavy build phase**, on every
   build platform and on integration-test nodes.
2. **Bound build retention** so old artifacts release their footprint
   automatically.
3. **Saturate per-agent parallelism** so build wall-clock isn't artificially
   throttled by a hardcoded `CB_NUMBER_OF_JOBS=4`.
4. **Report system findings** (free GB, cores detected, jobs chosen) in the
   build log on every run, even on success, so operators have post-mortem
   evidence without ad-hoc instrumentation.

## Non-goals

- Node monitor / agent offline configuration (operator action in Jenkins UI;
  out of pipeline code scope).
- Nightly sweep job (separate Jenkinsfile; see `jenkins-disk-defense.md` §5).
- SDK-side build footprint reduction (separate workstream).
- Differentiating preflight failures from real build failures so siblings
  don't abort — accepted as a known cost; see Trade-offs §1.

## Components

### A. `ensureDiskSpace(int minGB)`

New top-level helper, lives near `ensurePython()`. Pure sh + powershell — no
Python dependency, so preflight can run before `ensurePython()` if needed.
Always prints the measured free GB even on success.

```groovy
def ensureDiskSpace(int minGB) {
    if (isUnix()) {
        sh """
            set -euo pipefail
            free_kb=\$(df -k . | tail -1 | awk '{print \$4}')
            free_gb=\$((free_kb / 1024 / 1024))
            echo "ensureDiskSpace: \$free_gb GB free on workspace drive (node=\${NODE_NAME:-?}, threshold=${minGB} GB)"
            if [ "\$free_gb" -lt ${minGB} ]; then
                echo "ERROR: insufficient disk on \${NODE_NAME:-?} (\$free_gb GB free, need >= ${minGB} GB). Failing fast so another agent can take this build." >&2
                exit 1
            fi
        """
    } else {
        powershell """
            \$ErrorActionPreference = 'Stop'
            \$drive   = (Get-Location).Drive.Name
            \$free_gb = [int][math]::Floor((Get-PSDrive -Name \$drive).Free / 1GB)
            Write-Host "ensureDiskSpace: \$free_gb GB free on workspace drive (node=\$env:NODE_NAME, drive=\${drive}:, threshold=${minGB} GB)"
            if (\$free_gb -lt ${minGB}) {
                throw "insufficient disk on \$env:NODE_NAME (\$free_gb GB free, need >= ${minGB} GB). Failing fast so another agent can take this build."
            }
        """
    }
}
```

**Output examples:**

- Success: `ensureDiskSpace: 87 GB free on workspace drive (node=build-window-sdk-04, drive=C:, threshold=40 GB)`
- Failure: success line plus `ERROR: insufficient disk on build-window-sdk-04 (24 GB free, need >= 40 GB). Failing fast so another agent can take this build.` then non-zero exit.

### B. Per-platform threshold table

```groovy
def DISK_THRESHOLD_GB = [
    "rockylinux9":       15,
    "macos":             15,
    "m1":                15,
    "alpine3.21":        15,
    "msvc-2022":         40,    // gRPC+protobuf+boringssl Debug PDBs (per jenkins-disk-defense.md §1)
    "qe-rhel9-arm64":    15,
    "qe-ubuntu24-amd64": 15,
    "qe-ubuntu24-arm64": 15,
]
def INTEGRATION_DISK_THRESHOLD_GB = 20    // 3× Couchbase server Docker images + cluster runtime data
```

Top-of-file constants, immediately after `COMBINATION_PLATFORM`. Tuning is a
one-file edit.

### C. `computeJobs()`

Returns the chosen `CB_NUMBER_OF_JOBS` value (cores − 2, floor of 1) as a
string for env interpolation. Self-reports cores detected + jobs chosen to
stderr (visible in build log) while stdout carries the integer value.

```groovy
def computeJobs() {
    if (isUnix()) {
        return sh(returnStdout: true, script: '''
            cores=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)
            jobs=$(( cores > 2 ? cores - 2 : 1 ))
            echo "computeJobs: $cores cores detected on ${NODE_NAME:-?}, using $jobs parallel jobs (cores - 2, min 1)" >&2
            echo $jobs
        ''').trim()
    } else {
        return powershell(returnStdout: true, script: '''
            $cores = [int]$env:NUMBER_OF_PROCESSORS
            $jobs  = if ($cores -gt 2) { $cores - 2 } else { 1 }
            [Console]::Error.WriteLine("computeJobs: $cores cores detected on $env:NODE_NAME, using $jobs parallel jobs (cores - 2, min 1)")
            Write-Output $jobs
        ''').trim()
    }
}
```

**Output example:** `computeJobs: 16 cores detected on build-window-sdk-04, using 14 parallel jobs (cores - 2, min 1)`

### D. Pipeline-wide retention via `properties([buildDiscarder(...)])`

```groovy
properties([
    buildDiscarder(logRotator(
        numToKeepStr: '50',           // keep last 50 builds' logs
        artifactNumToKeepStr: '10',   // keep artifacts from last 10
        daysToKeepStr: '30'           // hard cap at 30 days
    ))
])
```

Lives at line 1 of the Jenkinsfile. The first build with this pipeline writes
the policy into the job config; subsequent builds are idempotent.

### E. Cores in per-platform prep diagnostic

The per-platform prep diagnostic (line ~262 Linux/macOS, line ~234 Windows)
currently dumps gcc / cmake / git versions but not core count. Add:

- Linux/macOS: `nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null`
- Windows: `"cores: $env:NUMBER_OF_PROCESSORS"`

Informational only; preserves the existing `set +e` / `Get-Command
-ErrorAction SilentlyContinue` best-effort convention. Goal: when triaging,
the prep section's snapshot includes the number that `computeJobs()` then
acted on.

## File layout

```
line 1     properties([buildDiscarder(...)])         ← NEW (job-wide retention)
line 3     def CMAKE_VERSION = "3.31.8"              ← existing
line ~12   def PLATFORMS                             ← existing
line ~22   def CB_VERSIONS                           ← existing
line ~33   def COMBINATION_PLATFORM                  ← existing
line ~35   def DISK_THRESHOLD_GB = [ ... ]           ← NEW
line ~46   def INTEGRATION_DISK_THRESHOLD_GB = 20    ← NEW
line ~50   def checkout()                            ← existing
line ~75   def checkoutPipelineRepo()                ← existing
line ~95   def ensureCbdinocluster()                 ← existing
line ~120  def ensurePython()                        ← existing
line ~140  def ensureDiskSpace(int minGB)            ← NEW
line ~170  def computeJobs()                         ← NEW
           (stages start below)
```

## Call sites

### 1. Build matrix per-platform prep stage (line ~227)

`ensureDiskSpace` is the **first** action inside `timeout(45) { stage("prep") {
... } }`, before the diagnostic dump. Fails the platform branch in ~5 s
instead of after 20 min of compile work when the agent is full.

```groovy
timeout(unit: 'MINUTES', time: 45) {
    stage("prep") {
        ensureDiskSpace(DISK_THRESHOLD_GB[platform])    // NEW

        // Per-node toolchain report ...
        if (platform == "msvc-2022") {
            powershell '''
                ...existing diagnostic dump...
                "cores: $env:NUMBER_OF_PROCESSORS"     // NEW
                ...
            '''
        } else {
            sh '''
                ...existing diagnostic dump...
                echo "cores: $(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null)"   // NEW
                ...
            '''
        }
        ...
```

### 2. Build matrix per-platform build stage (line ~306)

Replace the hardcoded `CB_NUMBER_OF_JOBS=4` with the dynamic value:

```groovy
stage("build") {
    def envs = ["CB_NUMBER_OF_JOBS=${computeJobs()}"]    // was: CB_NUMBER_OF_JOBS=4
    ...
```

### 3. Integration test docker stage (line ~510)

`ensureDiskSpace` runs **after** `deleteDir()` (which frees disk from the
prior workspace) and **before** `checkoutPipelineRepo()`. Uses the
integration-specific threshold (20 GB) to cover multi-GB Couchbase server
Docker image pulls × 3 nodes per cluster.

```groovy
timeout(unit: 'MINUTES', time: 15) {
    deleteDir()
    ensureDiskSpace(INTEGRATION_DISK_THRESHOLD_GB)    // NEW
    checkoutPipelineRepo()
    sh("docker --version")
    ensurePython()
    ensureCbdinocluster()
    ...
```

### 4. Integration test Capella stage (line ~733)

Same call, same position:

```groovy
timeout(unit: 'MINUTES', time: 15) {
    deleteDir()
    ensureDiskSpace(INTEGRATION_DISK_THRESHOLD_GB)    // NEW
    checkoutPipelineRepo()
    sh("docker --version")
    ensurePython()
    ensureCbdinocluster()
    ...
```

### NOT changed: prepare-and-validate stage

The prepare-and-validate node builds a small source tarball via `cmake
--target packaging_tarball` — essentially `ls-files | tar`, needs only 1-2
GB. The existing `cleanWs()` already runs there; adding `ensureDiskSpace`
would add no real value at this stage. If pressure ever appears here, one
extra `ensureDiskSpace(5)` call addresses it.

## Trade-offs

### 1. `failFast = true` on the build matrix still aborts healthy siblings on preflight failure

When `msvc-2022`'s preflight fails, the in-flight `rockylinux9` build on a
different physical agent is aborted too. This is wasteful — a full Windows
agent says nothing about a healthy Linux agent's capacity.

**Rationale for accepting:** distinguishing preflight failures from real
build failures requires a custom exception type that the `parallel(builds)`
coordinator doesn't trip on. That's a larger refactor than the disk-defense
scope. Pragmatic workaround: Jenkins rotates agent assignments on re-run, so
a manual rebuild typically picks a different agent for the failed slot.

**Mitigation:** add an in-code comment explaining the deliberate choice so a
future reader doesn't "fix" it by silencing the failure.

### 2. 30-day retention cap may foreclose long-range comparisons

`buildDiscarder` with `daysToKeepStr: '30'` means a build from 60 days ago is
no longer downloadable. For an active pipeline that's the right trade-off;
for a quarterly comparison, it isn't.

**Tuning knob:** bump `daysToKeepStr` if anyone needs longer windows.
**Counterweight:** without retention, agents accumulate artifacts
indefinitely — which is precisely the failure mode this design defends
against. Some retention floor is unavoidable.

### 3. `cores - 2` doesn't account for RAM-per-core

A hypothetical 32-core / 16 GB agent would receive `jobs=30`, which would
thrash. We assume agents are sized with reasonable RAM-per-core ratios (2-4
GB/core in current Couchbase build infrastructure).

**Fallback if thrashing appears:** the formula evolves to `max(2, min(cores
- 2, total_ram_gb / 4))`. Easy follow-up; not blocking.

### 4. Linux/macOS threshold of 15 GB is conservative

The runbook only profiled Windows agents. If Linux agents routinely run
<30 GB free (shared with other pipelines, e.g.), 15 GB may fire too often.

**Tuning knob:** values are in the `DISK_THRESHOLD_GB` table at the top of
the file — one-line edit per platform. No helper code changes.

### 5. Integration test 20 GB threshold assumes default Docker root

The check measures free space on the workspace volume. If a non-default
Docker root is configured *inside* the workspace, the check still reads the
right volume. If Docker is configured to pull into a *different* volume
than the workspace, the threshold may report misleading information
(workspace free space, not Docker-pull-destination free space). On
Couchbase's standard sdkqe images Docker root is `/var/lib/docker`, on the
same volume as the workspace, so this is not an issue today.

## Testing plan

Three independent verifications, one per defense tier:

### `ensureDiskSpace` (tier §1)

Manual, one-shot:
1. On a test agent of each platform family (Linux, macOS, Windows), fill the
   workspace drive to within `minGB` of full:
   - Linux/macOS: `dd if=/dev/zero of=/tmp/junk bs=1G count=$(($(df -BG --output=avail . | tail -1 | tr -d 'G ') - 5))`
   - Windows: `fsutil file createnew C:\junk 40000000000` (adjust size)
2. Trigger a pipeline build assigned to that agent.
3. Confirm the preflight stage fails in under 10 seconds with the expected
   error message including the node name, free GB, and threshold GB.
4. Delete the junk file; confirm subsequent builds succeed.

### `computeJobs` (tier §6)

Automatic on first build with the new pipeline:
1. Trigger any build.
2. Confirm the build log shows a line of the form `computeJobs: N cores
   detected on <agent>, using N-2 parallel jobs (cores - 2, min 1)` for each
   build node.
3. Confirm the subsequent `cmake --build . --parallel N` (Windows) or
   `bin/build-tests` log line uses the same `N`.
4. **Wall-clock check:** the Windows build stage should drop from ~120 min
   (the doc's observed time at `/m:4`) to 25-40 min on a 16-core agent.

### `buildDiscarder` (tier §4)

Configuration-level:
1. Trigger one build with the new pipeline.
2. Open the job UI → **Configure** → confirm a "Discard old builds" entry
   appears with `Days to keep: 30`, `Max # of builds: 50`, `Days to keep
   artifacts: <empty>`, `Max # of builds to keep with artifacts: 10`.
3. Long-tail verification (not testable in CI): after 51 builds accumulate,
   build #1 should no longer be listed in the build history. After 31 days,
   any build older than that should be gone.

## Rollout

All five components ship in one commit since they're a coherent defense
posture. Order of edits in the commit (for diff readability):

1. Add `properties([buildDiscarder(...)])` at line 1.
2. Add `DISK_THRESHOLD_GB` table + `INTEGRATION_DISK_THRESHOLD_GB` constant.
3. Add `ensureDiskSpace` and `computeJobs` helpers next to existing
   `ensure*` definitions.
4. Add cores line to per-platform prep diagnostics.
5. Add `ensureDiskSpace` call in the build matrix prep stage.
6. Replace `CB_NUMBER_OF_JOBS=4` with `CB_NUMBER_OF_JOBS=${computeJobs()}`.
7. Add `ensureDiskSpace` calls in both integration-test stages (docker +
   Capella).

After landing, monitor the next 5-10 builds for:

- The reported free-GB number on each agent (capacity trending baseline).
- The reported cores / jobs numbers per platform (validate against agent
  inventory).
- Whether any preflight fires; if so, that agent needs operator attention
  (or threshold tuning).
