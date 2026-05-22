# Disk-Pressure Defense — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the approved disk-pressure defense design (see `cxx-pipeline-disk-defense-design.md`) into `cxx/cxx-scripted-build-pipeline.groovy` — preflight disk gates, per-platform parallelism via `computeJobs()`, pipeline-wide build retention, and self-reporting observability throughout.

**Architecture:** Two new top-level Groovy helpers (`ensureDiskSpace`, `computeJobs`) using pure sh + powershell so they have no Python dependency; two new constants (per-platform threshold map + integration-node threshold); one `properties([buildDiscarder(...)])` block at file head; four call-site edits (build-matrix prep, build-matrix build, two integration-test stages); two prep-diagnostic augmentations (cores line on Linux/macOS and Windows).

**Tech Stack:** Groovy (scripted Jenkins pipeline), bash, PowerShell. Existing file: `cxx/cxx-scripted-build-pipeline.groovy`. Related spec: `cxx-pipeline-disk-defense-design.md`. Operational runbook: `jenkins-disk-defense.md`.

---

## Pre-flight: confirm starting state

These should hold at the start of Task 1. If they don't, stop and resync.

- [ ] **Step 0.1: Verify branch and clean working tree**

Run:
```
git status
```
Expected: on `cxx-rework-scripted-build-pipeline-for-cbdinocluster-tarball-ci` (or wherever the design spec was committed); no modified files; HEAD at `065023c cxx: design spec for disk-pressure defense` (or later).

- [ ] **Step 0.2: Verify the file we'll be editing parses (brace balance)**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `open=N close=N diff=0` for some N (recently 204). If non-zero, stop — the file is already broken and the plan won't reach a clean state.

- [ ] **Step 0.3: Confirm where the existing helpers live**

Run:
```
grep -nE '^def (checkout|ensure|compute)' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: matches for `def checkout()`, `def checkoutPipelineRepo()`, `def ensureCbdinocluster()`, `def ensurePython()`. New helpers will land alphabetically near these.

---

## Task 1: Add pipeline-wide `properties([buildDiscarder(...)])`

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy:1`

**Rationale:** One block at the top of the Jenkinsfile durably caps log/artifact retention for every future build. Doc tier §4. No call-site changes elsewhere.

- [ ] **Step 1.1: Edit the file head**

Open `cxx/cxx-scripted-build-pipeline.groovy`. Insert at the very top, before `def CMAKE_VERSION = "3.31.8"`, the following block (and a blank line after it):

```groovy
// Pipeline-wide retention cap. Bounds Jenkins controller disk usage:
// each build's logs / archived artifacts release their footprint
// automatically. numToKeepStr caps build-log retention; artifactNum-
// KeepStr caps the heavier per-build artifact retention (which
// dominates the on-disk footprint after archiveArtifacts of CMake
// logs and cbdinocluster collect-logs zips); daysToKeepStr is the
// hard cap. Tune via job-level review if anyone needs longer
// comparison windows. See cxx-pipeline-disk-defense-design.md §D.
properties([
    buildDiscarder(logRotator(
        numToKeepStr: '50',
        artifactNumToKeepStr: '10',
        daysToKeepStr: '30'
    ))
])

def CMAKE_VERSION = "3.31.8"
```

- [ ] **Step 1.2: Verify brace balance**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`. `open` and `close` should each be 1 higher than the baseline (the new `logRotator(...)` adds matched parens but not braces; the `properties([...])` adds matched square brackets but not braces; so the count actually changes by 0 if I'm counting only `{}` — re-confirm by running and noting the values.)

- [ ] **Step 1.3: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: pipeline-wide buildDiscarder for retention cap

Adds properties([buildDiscarder(logRotator(...))]) at the top of
cxx-scripted-build-pipeline.groovy: 50 builds, 10 artifact sets,
30-day hard cap. Bounds Jenkins controller disk usage so old builds
release their footprint automatically. Doc tier §4. See
cxx-pipeline-disk-defense-design.md §D.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Add threshold constants

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy` (after `def COMBINATION_PLATFORM = "rockylinux9"`)

**Rationale:** Centralizing thresholds at top-of-file means tuning is a one-line edit per platform. Used by `ensureDiskSpace` calls in Tasks 6, 8, 9.

- [ ] **Step 2.1: Add the per-platform map and integration constant**

Locate the line `def COMBINATION_PLATFORM = "rockylinux9"`. Immediately after that line (and the blank line that follows it, before `def checkout()`), insert:

```groovy
// Per-platform disk threshold (GB free on workspace drive) required
// before a build proceeds. Tuning knob lives here so per-platform
// changes don't require helper edits. msvc-2022 needs the largest
// budget — Debug PDBs for gRPC + protobuf + boringssl total ~25 GB
// per the runbook. 15 GB is conservative for Linux/macOS gRPC builds
// (~5-8 GB observed for _deps/ + build output, doubled for headroom).
// See cxx-pipeline-disk-defense-design.md §B.
def DISK_THRESHOLD_GB = [
    "rockylinux9":       15,
    "macos":             15,
    "m1":                15,
    "alpine3.21":        15,
    "msvc-2022":         40,
    "qe-rhel9-arm64":    15,
    "qe-ubuntu24-amd64": 15,
    "qe-ubuntu24-arm64": 15,
]
// Integration test nodes need headroom for cbdinocluster Docker image
// pulls (3 nodes × ~1.5 GB CB server image + runtime data + build
// artifact unstash).
def INTEGRATION_DISK_THRESHOLD_GB = 20
```

- [ ] **Step 2.2: Verify brace balance unchanged**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`. (The map literal `[...]` uses square brackets, not braces; no change in `{}` count.)

- [ ] **Step 2.3: Verify constants reachable from where they'll be used (Groovy quirk)**

In a scripted Jenkinsfile, top-level `def`s are local variables to the script's main body. They ARE in scope from inside any `node {}` / `stage {}` / closure that runs during script execution. Confirm by running:

```
grep -nE 'PLATFORMS|CB_VERSIONS|COMBINATION_PLATFORM' cxx/cxx-scripted-build-pipeline.groovy | head
```
Expected: the existing `PLATFORMS`, `CB_VERSIONS`, `COMBINATION_PLATFORM` are read inside `node {}` blocks. Our new `DISK_THRESHOLD_GB` and `INTEGRATION_DISK_THRESHOLD_GB` follow the same pattern, so they'll resolve the same way.

- [ ] **Step 2.4: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: add per-platform disk threshold table

Adds DISK_THRESHOLD_GB map (rocky/alpine/macos/m1/ubuntu: 15 GB;
msvc-2022: 40 GB) and INTEGRATION_DISK_THRESHOLD_GB=20 constant near
the top of cxx-scripted-build-pipeline.groovy. Centralised tuning
knob for the ensureDiskSpace gates added in subsequent commits.
See cxx-pipeline-disk-defense-design.md §B.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add `ensureDiskSpace(int minGB)` helper

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy` (after `def ensurePython()`)

**Rationale:** Cross-platform disk-space gate. Pure sh + powershell so it can run before `ensurePython()`. Always logs measured free GB even on success. Used in Tasks 6, 8, 9.

- [ ] **Step 3.1: Smoke-test the Linux body locally first**

Before pasting into Groovy, verify the sh snippet works standalone. Run:

```
bash -c '
    set -euo pipefail
    free_kb=$(df -k . | tail -1 | awk "{print \$4}")
    free_gb=$((free_kb / 1024 / 1024))
    echo "ensureDiskSpace: $free_gb GB free on workspace drive (node=${NODE_NAME:-?}, threshold=1 GB)"
    if [ "$free_gb" -lt 1 ]; then
        echo "ERROR: insufficient disk on ${NODE_NAME:-?} ($free_gb GB free, need >= 1 GB). Failing fast so another agent can take this build." >&2
        exit 1
    fi
'
```
Expected: prints one line "ensureDiskSpace: N GB free on workspace drive (node=?, threshold=1 GB)" for some N >= 1, exits 0.

If `free_gb` is suspiciously 0, your local `df` may not understand `-k` or have unusual output — the helper assumes GNU/BSD `df` which is universal on Couchbase build agents but worth knowing.

- [ ] **Step 3.2: Locate insertion point**

Run:
```
grep -nE '^def (ensurePython|ensureDiskSpace)' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: shows `def ensurePython()` only. We'll add `ensureDiskSpace` right after the closing brace of `ensurePython`.

- [ ] **Step 3.3: Edit — add the helper**

After the closing `}` of `def ensurePython()` and its trailing blank line, before the `stage("prepare and validate") {` line, insert:

```groovy
// Hard-gate workspace free space. Pure sh / powershell so this can
// run BEFORE ensurePython() if needed — preflight should not depend
// on Python being present. Always prints the measured number on
// success so capacity planning has data without ad-hoc
// instrumentation. On failure: non-zero exit with a clear message
// including node name, observed free GB, and threshold. Per-platform
// thresholds live in DISK_THRESHOLD_GB / INTEGRATION_DISK_THRESHOLD_GB
// at the top of the file. See cxx-pipeline-disk-defense-design.md §A.
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

(Mind the Groovy escaping: `\$` for shell-side `$`, `${minGB}` for Groovy-side parameter interpolation. Same convention as existing `ensureCbdinocluster` did pre-Pythonization.)

- [ ] **Step 3.4: Verify brace balance**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`. New `def ensureDiskSpace(...) { ... }` adds one matched pair; the `if (isUnix()) { ... } else { ... }` adds two matched pairs. All paired in the snippet above.

- [ ] **Step 3.5: Confirm the function definition is syntactically valid**

Run:
```
grep -nA1 '^def ensureDiskSpace' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: matches the new `def ensureDiskSpace(int minGB) {` line followed by `if (isUnix()) {`.

- [ ] **Step 3.6: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: add ensureDiskSpace(int minGB) helper

Cross-platform workspace-free-space gate. Pure sh / powershell so it
runs before ensurePython(). Always reports measured free GB on
success; on failure exits non-zero with a clear message including
node name, observed free GB, and threshold. Used by build-matrix
prep + integration-test stages in subsequent commits.
See cxx-pipeline-disk-defense-design.md §A.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add `computeJobs()` helper

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy` (after `def ensureDiskSpace(...)`)

**Rationale:** Returns the chosen `CB_NUMBER_OF_JOBS` value (cores − 2, floor of 1) as a String. Self-reports cores detected + jobs chosen to stderr (visible in console log) while stdout carries the integer for Groovy capture. Used in Task 7.

- [ ] **Step 4.1: Smoke-test the Linux body locally**

Run:
```
sh -c '
    cores=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)
    jobs=$(( cores > 2 ? cores - 2 : 1 ))
    echo "computeJobs: $cores cores detected on ${NODE_NAME:-?}, using $jobs parallel jobs (cores - 2, min 1)" >&2
    echo $jobs
'
```
Expected: a single integer on stdout (`echo "$RESULT"` should produce just the number), and the human-readable line on stderr. To verify the split, capture each separately:

```
STDOUT=$(bash -c '
    cores=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)
    jobs=$(( cores > 2 ? cores - 2 : 1 ))
    echo "computeJobs: $cores cores detected on ${NODE_NAME:-?}, using $jobs parallel jobs (cores - 2, min 1)" >&2
    echo $jobs
' 2>/tmp/_cj_err); echo "STDOUT=$STDOUT"; echo "STDERR=$(cat /tmp/_cj_err)"; rm /tmp/_cj_err
```
Expected: `STDOUT=N` for the integer N, and `STDERR=computeJobs: M cores detected on ?, using N parallel jobs (cores - 2, min 1)` where N = max(1, M - 2).

- [ ] **Step 4.2: Edit — add the helper**

After the closing `}` of `def ensureDiskSpace(...)` and its trailing blank line, insert:

```groovy
// Returns the chosen CB_NUMBER_OF_JOBS for this agent (cores - 2, min
// 1) as a String for env interpolation. Self-reports cores detected +
// jobs chosen to stderr so the build log records which agent provided
// which capacity. Stdout carries the integer (captured by Groovy via
// returnStdout); stderr is the human-readable explanation. Pure
// sh / powershell — no Python dependency. See
// cxx-pipeline-disk-defense-design.md §C.
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

- [ ] **Step 4.3: Verify brace balance**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`. The Groovy adds 1 pair for `def`, 1 pair for `if/else`. The PowerShell `if ($cores -gt 2) { ... } else { ... }` is INSIDE a `'''...'''` Groovy string and is NOT counted by our awk brace-counter — which is fine; awk only sees Groovy-level braces.

- [ ] **Step 4.4: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: add computeJobs() helper

Returns cores - 2 (min 1) for this agent as a String for env
interpolation. Self-reports cores detected + jobs chosen to stderr
so the build log records the per-agent reasoning. Pure sh /
powershell. Replaces the hardcoded CB_NUMBER_OF_JOBS=4 at call site
in the next commit. See cxx-pipeline-disk-defense-design.md §C.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Add cores line to per-platform prep diagnostics

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy` (Windows powershell block at line ~234; Linux/macOS sh block at line ~262)

**Rationale:** Make the cores number visible in the prep diagnostic dump so triage of a wrong-`computeJobs`-result has both the system view and the computed view. Informational only; preserves the existing best-effort `set +e` / `-ErrorAction SilentlyContinue` convention.

- [ ] **Step 5.1: Locate the Windows prep diagnostic block**

Run:
```
grep -nB1 '"PowerShell \\$(\\$PSVersionTable.PSVersion)"' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: one match around line ~242 inside the `if (platform == "msvc-2022")` branch.

- [ ] **Step 5.2: Edit Windows prep — add cores line**

In the powershell `'''...'''` block of the `msvc-2022` prep diagnostic, locate this existing region (around line 242):

```
                            & git --version 2>&1
                            "PowerShell $($PSVersionTable.PSVersion)"
                            $py = Get-Command python -ErrorAction SilentlyContinue
```

Replace with:

```
                            & git --version 2>&1
                            "PowerShell $($PSVersionTable.PSVersion)"
                            "cores: $env:NUMBER_OF_PROCESSORS"
                            $py = Get-Command python -ErrorAction SilentlyContinue
```

(The new line `"cores: $env:NUMBER_OF_PROCESSORS"` mirrors the same idiom used for `"PowerShell $($PSVersionTable.PSVersion)"` — a bare PowerShell expression that prints as output.)

- [ ] **Step 5.3: Locate the Linux/macOS prep diagnostic block**

Run:
```
grep -nB1 'make --version' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: one match around line ~276 inside the `else` branch (non-msvc-2022 platforms).

- [ ] **Step 5.4: Edit Linux/macOS prep — add cores line**

Locate this existing region:

```
                            make --version      2>/dev/null | head -1
                            python3 --version   2>/dev/null
                            exit 0
```

Replace with:

```
                            make --version      2>/dev/null | head -1
                            python3 --version   2>/dev/null
                            echo "cores: $(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null)"
                            exit 0
```

(The `||` fallback covers macOS where `nproc` isn't installed; `sysctl -n hw.ncpu` is the BSD/macOS equivalent.)

- [ ] **Step 5.5: Verify brace balance**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`. Both edits are inside `'''...'''` blocks — no Groovy-level braces added.

- [ ] **Step 5.6: Confirm both diagnostics now emit cores**

Run:
```
grep -n 'cores' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: at least three matches — the existing `echo "cores: $(nproc 2>/dev/null)"` line ~166 in prepare-and-validate's env stage, the new Windows line, and the new Linux/macOS line.

- [ ] **Step 5.7: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: report cores in per-platform prep diagnostic

Adds a "cores: N" line to the per-platform prep diagnostic block on
both Windows (powershell, $env:NUMBER_OF_PROCESSORS) and Linux/macOS
(sh, nproc with sysctl fallback). Aligns the build matrix diagnostic
with what prepare-and-validate's env stage already dumps. Lets a
post-mortem reviewer see the cores number that fed into computeJobs.
See cxx-pipeline-disk-defense-design.md §E.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Wire `ensureDiskSpace` into build-matrix prep

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy` (per-platform prep, line ~227 — first action inside `timeout(45) { stage("prep") { ... } }`)

**Rationale:** Hard-gate the build matrix per-platform on disk free. Threshold from `DISK_THRESHOLD_GB[platform]`. Fails the platform branch in ~5 s if the agent is full.

- [ ] **Step 6.1: Locate the prep stage opening**

Run:
```
grep -nB1 -A2 'stage("prep") {' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: one match around line 227, immediately inside `timeout(unit: 'MINUTES', time: 45) {`.

- [ ] **Step 6.2: Edit — add the call as the first action**

Locate this existing region:

```
                timeout(unit: 'MINUTES', time: 45) {
                stage("prep") {
                    // Per-node toolchain report: what THIS build node actually has, not just what
```

Replace with:

```
                timeout(unit: 'MINUTES', time: 45) {
                stage("prep") {
                    // Pre-flight disk gate. Per-platform threshold from DISK_THRESHOLD_GB
                    // (msvc-2022: 40 GB for gRPC+protobuf+boringssl PDB output; others: 15 GB).
                    // Fails in ~5 s if the agent is full instead of after ~20 min of compile
                    // work — see cxx-pipeline-disk-defense-design.md §1.
                    ensureDiskSpace(DISK_THRESHOLD_GB[platform])

                    // Per-node toolchain report: what THIS build node actually has, not just what
```

- [ ] **Step 6.3: Verify brace balance**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`.

- [ ] **Step 6.4: Verify the call is reachable from the iteration variable `platform`**

Run:
```
grep -nE 'ensureDiskSpace\(DISK_THRESHOLD_GB' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: one match. `platform` is in scope from the surrounding `for (p in PLATFORMS) { def platform = p; builds[platform] = { node(platform) { timeout(...) { stage("prep") { ... } } } }` closure capture; Groovy resolves it via the closure context.

- [ ] **Step 6.5: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: pre-flight disk gate in build-matrix prep

Calls ensureDiskSpace(DISK_THRESHOLD_GB[platform]) as the first
action inside each platform's prep stage. Fails fast (~5 s) on a
full agent instead of after ~20 min of compile work. msvc-2022 gets
the 40 GB threshold from the doc; other platforms get 15 GB.
See cxx-pipeline-disk-defense-design.md §1.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Replace hardcoded `CB_NUMBER_OF_JOBS=4` with `computeJobs()`

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy:306`

**Rationale:** Doc tier §6. Replace the universal 4-wide cap with the per-agent cores-minus-2 result. Windows wall-clock should drop 3-5× on 16-core agents.

- [ ] **Step 7.1: Locate the existing envs line**

Run:
```
grep -nE 'CB_NUMBER_OF_JOBS=4' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: one match at line ~306, in the form `def envs = ["CB_NUMBER_OF_JOBS=4"]`.

- [ ] **Step 7.2: Edit — replace the literal**

Locate:

```
                stage("build") {
                    def envs = ["CB_NUMBER_OF_JOBS=4"]
```

Replace with:

```
                stage("build") {
                    // Per-agent parallelism via computeJobs() (cores - 2, min 1). Replaces
                    // the previous CB_NUMBER_OF_JOBS=4 hardcode that capped every platform
                    // at 4-wide regardless of agent size. Self-reports cores detected +
                    // jobs chosen to the build log. See cxx-pipeline-disk-defense-design.md
                    // §2 + §C.
                    def envs = ["CB_NUMBER_OF_JOBS=${computeJobs()}"]
```

- [ ] **Step 7.3: Verify brace balance**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`. The new `${computeJobs()}` adds matched `{}` in the Groovy GString — counted by awk and matched.

- [ ] **Step 7.4: Confirm only one CB_NUMBER_OF_JOBS assignment remains**

Run:
```
grep -nE 'CB_NUMBER_OF_JOBS' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: two matches — the new `CB_NUMBER_OF_JOBS=${computeJobs()}` envs assignment, and the existing `cmake --build . --parallel $env:CB_NUMBER_OF_JOBS` line in the msvc-2022 build block. The previous hardcoded `=4` should be gone.

- [ ] **Step 7.5: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: replace CB_NUMBER_OF_JOBS=4 with computeJobs()

Lifts the previously-fixed 4-wide parallelism cap. computeJobs()
returns cores - 2 (min 1) per agent and reports the reasoning to
the build log. Expected wall-clock drop on the Windows stage:
~120 min → 25-40 min on 16-core agents per
jenkins-disk-defense.md §6. See cxx-pipeline-disk-defense-design.md §2.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Wire `ensureDiskSpace` into integration-test docker stage

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy` (docker stage prep, line ~510 area — after `deleteDir()`, before `checkoutPipelineRepo()`)

**Rationale:** Gate the docker-based integration test on the integration threshold (20 GB) so cbdinocluster's CB server Docker image pulls don't fail mid-bring-up. Placement: after `deleteDir()` (which frees disk from prior workspace).

- [ ] **Step 8.1: Locate the docker stage's deleteDir + checkoutPipelineRepo region**

Run:
```
grep -nA1 'deleteDir()' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: at least two matches. The relevant one is inside `cbverStages["${COMBINATION_PLATFORM}-${label}"]` — i.e. inside the per-CB-version closure, around line 511. Identify it by the comment "Clone the Jenkinsfile's repo into pipeline-scripts/" that follows.

- [ ] **Step 8.2: Edit — insert the gate**

Locate this existing region (inside the per-CB-version `stage(label)`):

```
                            timeout(unit: 'MINUTES', time: 15) {
                            deleteDir()
                            // Clone the Jenkinsfile's repo into pipeline-scripts/ so the
```

Replace with:

```
                            timeout(unit: 'MINUTES', time: 15) {
                            deleteDir()
                            // Pre-flight disk gate. INTEGRATION_DISK_THRESHOLD_GB covers
                            // 3× CB server Docker images (~1.5 GB each) + cluster runtime
                            // data + build artifact unstash. Runs after deleteDir() so the
                            // prior workspace has been freed, before checkoutPipelineRepo()
                            // adds the script clone. See cxx-pipeline-disk-defense-design.md §3.
                            ensureDiskSpace(INTEGRATION_DISK_THRESHOLD_GB)
                            // Clone the Jenkinsfile's repo into pipeline-scripts/ so the
```

- [ ] **Step 8.3: Verify brace balance**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`.

- [ ] **Step 8.4: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: pre-flight disk gate in docker integration test

Calls ensureDiskSpace(INTEGRATION_DISK_THRESHOLD_GB) after deleteDir()
and before checkoutPipelineRepo() in the per-CB-version integration
stage. Catches a full agent before cbdinocluster spins up its 3
Docker containers (each ~1.5 GB CB server image). 20 GB threshold.
See cxx-pipeline-disk-defense-design.md §3.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Wire `ensureDiskSpace` into integration-test Capella stage

**Files:**
- Modify: `cxx/cxx-scripted-build-pipeline.groovy` (Capella stage prep, line ~733 area — after `deleteDir()`, before `checkoutPipelineRepo()`)

**Rationale:** Same gate as Task 8, in the Capella stage. The Capella deployer doesn't pull Docker images, but the integration test node may still be tight on disk after prior builds; the gate is a cheap consistency safeguard.

- [ ] **Step 9.1: Locate the Capella stage's deleteDir + checkoutPipelineRepo region**

Run:
```
grep -nA2 'stage("capella") {' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: one match around line 728. The body opens with the bring-up timeout and `deleteDir()` shortly after.

- [ ] **Step 9.2: Edit — insert the gate**

Locate this existing region (inside `stage("capella")`):

```
                        timeout(unit: 'MINUTES', time: 15) {
                        deleteDir()
                        // Clone pipeline-scripts/ (see docker stage above for rationale).
                        checkoutPipelineRepo()
```

Replace with:

```
                        timeout(unit: 'MINUTES', time: 15) {
                        deleteDir()
                        // Same pre-flight as the docker stage — see Task 8 / design §3.
                        // The Capella deployer doesn't pull Docker images locally, but the
                        // sdkqe agent may still be tight on disk after prior builds, and
                        // a consistent gate everywhere keeps the trade-off legible.
                        ensureDiskSpace(INTEGRATION_DISK_THRESHOLD_GB)
                        // Clone pipeline-scripts/ (see docker stage above for rationale).
                        checkoutPipelineRepo()
```

- [ ] **Step 9.3: Verify brace balance**

Run:
```
awk 'BEGIN{o=0;c=0} {for(i=1;i<=length($0);i++){ch=substr($0,i,1); if(ch=="{")o++; if(ch=="}")c++}} END{print "open="o" close="c" diff="o-c}' cxx/cxx-scripted-build-pipeline.groovy
```
Expected: `diff=0`.

- [ ] **Step 9.4: Final reference scan — every design component is wired**

Run:
```
echo "=== threshold constants ==="
grep -nE 'DISK_THRESHOLD_GB|INTEGRATION_DISK_THRESHOLD_GB' cxx/cxx-scripted-build-pipeline.groovy | head -5
echo ""
echo "=== buildDiscarder ==="
grep -nE 'buildDiscarder' cxx/cxx-scripted-build-pipeline.groovy
echo ""
echo "=== ensureDiskSpace call sites ==="
grep -nE 'ensureDiskSpace\(' cxx/cxx-scripted-build-pipeline.groovy
echo ""
echo "=== computeJobs call sites ==="
grep -nE 'computeJobs\(\)' cxx/cxx-scripted-build-pipeline.groovy
echo ""
echo "=== cores diagnostic lines ==="
grep -n 'cores' cxx/cxx-scripted-build-pipeline.groovy
```
Expected:
- threshold constants: 2 matches (the `def DISK_THRESHOLD_GB = [` line and the `def INTEGRATION_DISK_THRESHOLD_GB = 20`) plus 3 use sites
- buildDiscarder: 1 match (the `properties([buildDiscarder(...)])` at line 1-area)
- ensureDiskSpace calls: 3 matches (build-matrix prep + docker integration + Capella integration), plus 1 def
- computeJobs calls: 1 match (the `CB_NUMBER_OF_JOBS=${computeJobs()}` line), plus 1 def
- cores: at least 3 matches (env stage + Windows prep + Linux prep)

- [ ] **Step 9.5: Commit**

```
git add cxx/cxx-scripted-build-pipeline.groovy
git commit -m "$(cat <<'EOF'
cxx: pre-flight disk gate in Capella integration test

Symmetric with the docker stage: calls
ensureDiskSpace(INTEGRATION_DISK_THRESHOLD_GB) after deleteDir() and
before checkoutPipelineRepo(). Same 20 GB threshold. Capella's
deployer doesn't pull Docker images locally, but the gate keeps the
trade-off consistent across integration test branches and catches
unrelated agent-disk drift.
See cxx-pipeline-disk-defense-design.md §3.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Post-implementation: end-to-end verification

These steps are **operator actions** — they require triggering a Jenkins build. Include them in the PR description for the on-call reviewer.

### V1: `ensureDiskSpace` fires correctly on success

- [ ] **Step V1.1:** Trigger any build of `cxx-scripted-build-pipeline` after merge.
- [ ] **Step V1.2:** Open the build's console log; search for `ensureDiskSpace:`. Expect at least 10 matches (8 build platforms + 2 integration stages × 5 CB versions… actually only the docker stage runs once per version, and the Capella stage runs once total, so: 8 build platforms + 5 docker + 1 Capella = 14 matches).
- [ ] **Step V1.3:** Each match should print `N GB free on workspace drive (node=<agent>, threshold=<T> GB)` for some N >= T. If any line shows N < T but the build still proceeded, the helper is broken — file a regression.

### V2: `computeJobs` fires correctly + parallelism increases on Windows

- [ ] **Step V2.1:** In the same build's console log, search for `computeJobs:`. Expect 8 matches (one per build platform).
- [ ] **Step V2.2:** Each match should print `M cores detected on <agent>, using N parallel jobs (cores - 2, min 1)` where N = max(1, M - 2).
- [ ] **Step V2.3:** Search for the subsequent `cmake --build` (msvc-2022) or `bin/build-tests` (Linux/macOS) log lines. The Windows one should show `--parallel <N>` matching the computeJobs output for that agent.
- [ ] **Step V2.4:** Compare the msvc-2022 build stage wall-clock against a recent pre-change build. Expected reduction: ~120 min → 25-40 min on a 16-core agent.

### V3: `buildDiscarder` is wired

- [ ] **Step V3.1:** Open the job in the Jenkins UI → **Configure**. Confirm the "Discard old builds" section now shows `Days to keep: 30`, `Max # of builds: 50`, `Days to keep artifacts: <empty>`, `Max # of builds to keep with artifacts: 10`.
- [ ] **Step V3.2:** No further action — retention will manifest passively over the next 50 builds.

### V4: `ensureDiskSpace` failure path (manual, one-shot per platform family)

- [ ] **Step V4.1:** Pick one Linux build agent, RDP/SSH in, fill its workspace drive to within ~5 GB of full:
```
dd if=/dev/zero of=/tmp/junk bs=1G count=$(($(df -BG --output=avail / | tail -1 | tr -d 'G ') - 10))
```
- [ ] **Step V4.2:** Trigger a build that lands on that agent (mark all other Linux agents temporarily offline).
- [ ] **Step V4.3:** Confirm the preflight stage fails in under 10 s with the expected message including the node name, free GB, and threshold.
- [ ] **Step V4.4:** Delete the junk file (`rm /tmp/junk`); confirm subsequent builds succeed.
- [ ] **Step V4.5:** Repeat for one Windows agent using `fsutil file createnew C:\junk <size>`.

---

## Self-review (run before declaring done)

These are mental checks I run after writing the plan — the user doesn't execute them, but they should pass before handing off.

**Spec coverage:**

| Spec section | Plan task |
|---|---|
| §A `ensureDiskSpace` | Task 3 |
| §B threshold table | Task 2 |
| §C `computeJobs` | Task 4 |
| §D `buildDiscarder` | Task 1 |
| §E cores diagnostic | Task 5 |
| Call site 1 (build-matrix prep) | Task 6 |
| Call site 2 (build-matrix build) | Task 7 |
| Call site 3 (docker integration) | Task 8 |
| Call site 4 (Capella integration) | Task 9 |
| Testing plan | V1-V4 |

✓ Every spec section maps to at least one task.

**Type consistency:**

- `ensureDiskSpace(int minGB)` signature is consistent between Task 3 definition and Tasks 6/8/9 call sites.
- `computeJobs()` returns a `String` (via `.trim()` on a `sh(returnStdout: true)`) — interpolated into a GString in Task 7 (`CB_NUMBER_OF_JOBS=${computeJobs()}`) which expects a String. ✓
- `DISK_THRESHOLD_GB[platform]` is a `Map<String, Integer>` access yielding `Integer`, passed to `ensureDiskSpace(int minGB)` — Groovy auto-unboxes. ✓
- `INTEGRATION_DISK_THRESHOLD_GB` is an `Integer` literal, passed to `ensureDiskSpace(int minGB)`. ✓

**Placeholder scan:** no TODO/TBD/XXX/FIXME in any task body. ✓

**File layout consistency:** insertion points described in the design's §"File layout" match the locations specified in Tasks 1-5. Tasks 6-9 reference existing-file line numbers (~227, ~306, ~510, ~733), correctly tagged with `~` as approximate.
