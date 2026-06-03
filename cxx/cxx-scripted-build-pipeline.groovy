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

// cbdinocluster version + per-asset SHA-256 map live in cxx/scripts/cbdinocluster.json
// (consumed by cxx/scripts/install_cbdinocluster.py). Bumping the cbdinocluster pin is
// a single-file edit there — version and every digest in lockstep. See ensureCbdinocluster()
// below for how the script is invoked from each agent.

// User-facing platform IDs (matrix entries; also the Jenkins job's
// PLATFORM choice parameter values) → Jenkins executor labels. The
// indirection lets the executor pool change without renaming what users
// pick in the build form, and removes the "what is m1?" / "is qe- part
// of the name?" surprises that the bare executor labels carry.
def PLATFORM_EXECUTOR = [
    "alpine3.21-amd64": "alpine3.21",
    "rocky9-amd64":     "rockylinux9",
    "ubuntu24-amd64":   "qe-ubuntu24-amd64",
    "rhel9-arm64":      "qe-rhel9-arm64",
    "ubuntu24-arm64":   "qe-ubuntu24-arm64",
    "macos14-amd64":    "macos",       // Sonoma
    "macos15-arm64":    "m1",          // Sequoia
    "win2022-amd64":    "msvc-2022",
]

// Resolve the PLATFORM Jenkins job parameter into the matrix entries
// this build will run. The job config exposes a Choice parameter whose
// values are the PLATFORM_EXECUTOR keys plus the literal "ALL", which
// is the default. "ALL" (and a defensively-handled empty value) expand
// to the full map; a specific platform ID narrows the matrix to one;
// anything else fails fast before any agent is allocated.
def PLATFORMS
if (!PLATFORM || PLATFORM == "ALL") {
    PLATFORMS = PLATFORM_EXECUTOR.keySet().toList()
} else if (PLATFORM_EXECUTOR.containsKey(PLATFORM)) {
    PLATFORMS = [PLATFORM]
} else {
    error("Unknown PLATFORM '${PLATFORM}'. Choose one of: ALL, ${PLATFORM_EXECUTOR.keySet().join(', ')}.")
}
// cbdinocluster needs concrete server versions: its versionident parses
// "X.Y.Z" or "X.Y.Z-<numeric build>", so the cbdyncluster-era channel
// aliases ("7.1-release", "7.6-stable") die in strconv.ParseInt — every
// alloc in build #11 failed with `failed to parse build number: parsing
// "release"`. tag: newest EE docker tag of the train; ceTag: newest
// Community tag of the same train (CE trails EE — e.g. 7.6 EE reaches
// 7.6.11 while CE stops at 7.6.2; checked against hub.docker.com
// couchbase/server tags, 2026-06-02). label keeps the old channel name
// for stage/branch display so run-to-run comparisons stay legible.
def CB_VERSIONS = [
    "70release": [tag: "7.0.5",  ceTag: "7.0.2",  label: "7.0-release"],
    "71release": [tag: "7.1.6",  ceTag: "7.1.1",  label: "7.1-release"],
    "72stable":  [tag: "7.2.9",  ceTag: "7.2.4",  label: "7.2-stable"],
    "76stable":  [tag: "7.6.11", ceTag: "7.6.2",  label: "7.6-stable"],
    "80stable":  [tag: "8.0.1",  ceTag: "8.0.1",  label: "8.0-stable"]
]
def COMBINATION_PLATFORM = "rocky9-amd64"
// Every node(...) expression in this file is a Jenkins LABEL, not a node
// identifier — and a label can match multiple physical agents. Jenkins
// picks one matching agent when a node() block enters; a SECOND node()
// with the same label may land on a DIFFERENT physical agent. So any
// state that must travel across stage boundaries either lives inside a
// single node() block (which is locked to one physical agent for its
// duration) or moves through stash / archiveArtifacts, which are
// content-addressed and routed via the Jenkins controller.
//
// The source tarball is platform- and binary-independent and is handed
// off via stash + archiveArtifacts (see the prepare-and-validate stage),
// so its prep node can use a wider pool: both the bare build-agent label
// (<executor>) and the test-agent label (sdkqe-<executor>). The other
// COMBINATION_PLATFORM stages — unit tests, integration-test cluster
// bring-up, Capella — consume the platform-specific build binary or need
// the test-agent toolchain (docker, cbdinocluster) and stay strict on
// sdkqe-. Their bring-up + test + cleanup also stay inside one node()
// block; see the cbverStages comments for why.
def TARBALL_LABEL = "${PLATFORM_EXECUTOR[COMBINATION_PLATFORM]} || sdkqe-${PLATFORM_EXECUTOR[COMBINATION_PLATFORM]}"

// Per-platform disk threshold (GB free on workspace drive) required
// before a build proceeds. Keyed by the user-facing platform IDs (the
// PLATFORM_EXECUTOR keys) — the build matrix iterates those, so the
// gate looks up DISK_THRESHOLD_GB[platform] directly. Tuning knob
// lives here so per-platform changes don't require helper edits.
// win2022-amd64 (msvc-2022) needs the largest budget — Debug PDBs for
// gRPC + protobuf + boringssl total ~25 GB per the runbook. 15 GB is
// conservative for Linux/macOS gRPC builds (~5-8 GB observed for
// _deps/ + build output, doubled for headroom).
// See cxx-pipeline-disk-defense-design.md §B.
def DISK_THRESHOLD_GB = [
    "alpine3.21-amd64": 15,
    "rocky9-amd64":     15,
    "ubuntu24-amd64":   15,
    "rhel9-arm64":      15,
    "ubuntu24-arm64":   15,
    "macos14-amd64":    15,
    "macos15-arm64":    15,
    "win2022-amd64":    40,
]
// Integration test nodes need headroom for cbdinocluster Docker image
// pulls (3 nodes × ~1.5 GB CB server image + runtime data + build
// artifact unstash).
def INTEGRATION_DISK_THRESHOLD_GB = 20


def checkout() {
    dir("couchbase-cxx-client") {
        checkout([
            $class: "GitSCM",
            branches: [[name: "$SHA"]],
            userRemoteConfigs: [[url: "$REPO", refspec: "$REFSPEC"]],
            extensions: [[
                $class: "SubmoduleOption",
                disableSubmodules: false,
                parentCredentials: false,
                recursiveSubmodules: true,
                reference: "",
                trackingSubmodules: false
            ]]
        ])
    }
}


// Clone the sdkbuild-jenkinsfiles repo (the one this Jenkinsfile loads from)
// into pipeline-scripts/ in the current node's workspace, so the helper Python
// scripts under cxx/scripts/ are available locally for invocation.
//
// `scm` is the SCM config Jenkins set for the job that loaded this file;
// re-using it means we don't hardcode a URL and PR/branch builds clone the
// same revision that produced this Jenkinsfile (correct under code review).
// Shallow + no-tags because we only need the file contents at one revision —
// the pipeline never inspects history. Submodule extensions from the original
// SCM config are intentionally dropped (cxx-client uses submodules, this repo
// doesn't).
//
// Idempotent in the sense that re-invoking on the same node is a fast
// `git fetch` against an existing checkout; in practice each node calls this
// exactly once per build, at the top of the integration-test stage.
def checkoutPipelineRepo() {
    checkout([
        $class: 'GitSCM',
        branches: scm.branches,
        userRemoteConfigs: scm.userRemoteConfigs,
        extensions: [
            [$class: 'RelativeTargetDirectory', relativeTargetDir: 'pipeline-scripts'],
            [$class: 'CloneOption', shallow: true, depth: 1, noTags: true]
        ]
    ])
}


// Report the executing agent's identity so triage can correlate a stage's
// output with the physical host that ran it. Especially useful where the
// node() label can match multiple physical agents (see the labels-vs-
// identifiers header near TARBALL_LABEL): the resolved NODE_NAME tells
// you which one Jenkins actually picked, and the *_LABELS values let you
// see the full label set the host advertised (often a superset of the
// label we asked for, which sometimes explains "why did *this* host
// answer?"). HOSTNAME and CONTAINER_TAG are populated by the agent
// provisioner and distinguish container-on-host situations where
// NODE_NAME alone is ambiguous.
def reportExecutingNode() {
    if (isUnix()) {
        sh '''
            echo "HOSTNAME=${HOSTNAME}"
            echo "NODE_NAME=${NODE_NAME}"
            echo "CONTAINER_TAG=${CONTAINER_TAG}"
            echo "JENKINS_SLAVE_LABELS=${JENKINS_SLAVE_LABELS}"
            echo "NODE_LABELS=${NODE_LABELS}"
        '''
    } else {
        powershell '''
            Write-Host "HOSTNAME=$env:COMPUTERNAME"
            Write-Host "NODE_NAME=$env:NODE_NAME"
            Write-Host "CONTAINER_TAG=$env:CONTAINER_TAG"
            Write-Host "JENKINS_SLAVE_LABELS=$env:JENKINS_SLAVE_LABELS"
            Write-Host "NODE_LABELS=$env:NODE_LABELS"
        '''
    }
}


// DIAGNOSTIC ONLY — answers "is this runner persistent, and would ccache
// help across builds?" (design: docs/superpowers/specs/
// 2026-06-02-runner-persistence-ccache-probe-design.md). Three probes:
//
//   1. Uptime, two ways. /proc/uptime is the HOST's uptime even inside a
//      container; mtime of /proc/1 is PID 1's start, i.e. the container
//      start (≈ boot time on a bare host). pid1_age << host_uptime means
//      "container on a long-lived host" — the case where a volume-mounted
//      CCACHE_DIR would pay off.
//   2. Build-visit markers: one empty file per build under
//      ${XDG_CACHE_HOME:-~/.cache}/cb-sdk-build-markers/$JOB_NAME. The
//      marker dir deliberately shares a survival domain with ccache's
//      default cache dir (~/.ccache or ~/.cache/ccache): if markers
//      accumulate across builds, a compiler cache would have survived too.
//      Count = builds this runner has executed; names = which and when.
//   3. Existing ccache version, stats (pre-build hit-rate history on
//      persistent runners), cache dir and its on-disk size.
//
// ccache is already wired into the build (cmake/Cache.cmake auto-detects
// it; bin/build-tests defaults CB_CACHE_OPTION=ccache) — this probe is
// measurement only, deciding WHERE the cache should live comes after a few
// builds of data. POSIX sh (dash/ash-safe); `set +e` + `exit 0` like the
// toolchain dump — missing tools (ccache, /proc on macOS) print nothing,
// never fail. sh-only by design: win2022-amd64 compiles with MSVC, no
// ccache, so a powershell variant has nothing to measure yet.
def reportPersistence() {
    if (isUnix()) {
        sh '''
            set +e
            echo "===== Runner persistence / ccache ====="
            uptime
            [ -f /proc/uptime ] && awk '{printf "host_uptime_seconds: %d\\n", $1}' /proc/uptime
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

            # Hit-rate hypothesis probe: ccache hashes compile paths absolutely
            # by default, so a build landing in a Jenkins @N concurrent
            # workspace (.../TEST-job@2/...) misses 100% against objects cached
            # from the @-less path. Record the workspace path this build got
            # and the config knobs that govern it (base_dir, hash_dir,
            # max_size) — if builds alternate between @-suffixed paths while
            # hits stay at 0%, that's the confirmation, and CCACHE_BASEDIR is
            # the fix.
            echo "workspace path: $(pwd) (WORKSPACE=${WORKSPACE:-unset})"
            ccache -p 2>/dev/null | grep -E "base_dir|hash_dir|max_size|cache_dir"
            exit 0
        '''
    }
    // No powershell branch: see header comment.
}


// Delete build intermediates the stash consumers can never need, keeping
// everything ctest requires at runtime. Rehearsed against a real build of
// the build-11 tarball: 7741 MB -> 2584 MB (-67%) with identical ctest
// enumeration (176 integration / 438 total), clean ldd, and 150/150 unit
// tests passing on the pruned tree. See
// docs/superpowers/specs/2026-06-02-stash-prune-design.md.
//
// What goes: CMakeFiles/ object trees (~3.1 GB), *.a archives (~1.3 GB —
// already linked into the binaries), _deps contents (~0.6 GB). What stays
// — all verified load-bearing: test/tool/example executables (ctest's
// integration label includes the example binaries!), the shared
// libcouchbase_cxx_client.so the tests link against, and the
// CTestTestfile.cmake chain INCLUDING inside _deps — the root testfile
// has subdirs("_deps/boringssl-build") etc., so ctest traversal breaks
// if those go.
//
// Loud on failure (set -eu / Stop) by design: these are mechanical
// deletions, and if they fail the workspace deserves a look rather than
// a silent fall-back to the 8-minute full-fat stash.
//
// Called from the build-matrix node after a successful build, as the
// last act before stash() — mutating the workspace is safe there. Runs
// on every platform (frees agent disk) even though only
// COMBINATION_PLATFORM stashes today.
def pruneForStash() {
    if (isUnix()) {
        // POSIX sh + portable find only (dash/ash/BSD): -prune/-exec
        // instead of GNU -delete/-empty. rmdir bottom-up removes the
        // emptied _deps dirs and leaves the ones still holding a
        // CTestTestfile.cmake (its non-zero exit is expected, hence
        // the || true).
        sh '''
            set -eu
            before=$(du -sm . | cut -f1)
            find cmake-build-tests -type d -name CMakeFiles -prune -exec rm -rf {} +
            find cmake-build-tests -type f -name "*.a" -exec rm -f {} +
            if [ -d cmake-build-tests/_deps ]; then
                find cmake-build-tests/_deps -type f ! -name "CTestTestfile.cmake" -exec rm -f {} +
                find cmake-build-tests/_deps -depth -type d -exec rmdir {} + 2>/dev/null || true
            fi
            after=$(du -sm . | cut -f1)
            echo "pruneForStash: ${before} MB -> ${after} MB"
        '''
    } else {
        // msvc-2022 builds in build/ (not cmake-build-tests/); objects
        // are *.obj, archives *.lib. SilentlyContinue on the CMakeFiles
        // pass: the recursive listing is materialized before deletion
        // starts, so nested CMakeFiles dirs may already be gone with
        // their parent.
        powershell '''
            $ErrorActionPreference = 'Stop'
            $before = [int]((Get-ChildItem -Recurse -File | Measure-Object Length -Sum).Sum / 1MB)
            Get-ChildItem build -Recurse -Directory -Filter CMakeFiles |
                Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
            Get-ChildItem build -Recurse -File | Where-Object { $_.Extension -in ".obj", ".lib" } |
                Remove-Item -Force
            if (Test-Path build/_deps) {
                Get-ChildItem build/_deps -Recurse -File | Where-Object { $_.Name -ne "CTestTestfile.cmake" } |
                    Remove-Item -Force
                Get-ChildItem build/_deps -Recurse -Directory | Sort-Object FullName -Descending |
                    Where-Object { -not (Get-ChildItem $_.FullName -Force) } | Remove-Item -Force
            }
            $after = [int]((Get-ChildItem -Recurse -File | Measure-Object Length -Sum).Sum / 1MB)
            Write-Host "pruneForStash: $before MB -> $after MB"
        '''
    }
}


// Best-effort docker-side post-mortem, run when cluster bring-up fails or
// times out. Build #13 proved what this is FOR: the servers came up fine
// (every node logged "Starting Couchbase Server", live processes in docker
// stats) yet the agent-side probe got HTTP 000 on 8091 for all of them —
// INCLUDING build #12's nodes that had been Up 2 hours. A 2-hour-old
// Couchbase is certainly listening, so 000 there is conclusive: the Jenkins
// agent container cannot ROUTE to the cbdynnode containers. The agent talks
// to the host docker daemon over the mounted socket, so nodes land on a host
// bridge network (172.19.0.0/16) the swarm-service agent container is not
// attached to; cbdinocluster's readiness poll runs inside the agent, hits
// the same 000, and spins until the bring-up cap fires.
//
// So this dumps three things, in increasing diagnostic power:
//   1. container status + host memory/disk pressure (rule out OOM / full disk)
//   2. per node: agent-side 8091/18091 probe AND a server-SELF probe run
//      inside the container (docker exec curl localhost) — the pair
//      disambiguates "server down" (both fail) from "agent can't route"
//      (self 200/401, agent 000), which is the build #13 signature.
//   3. network topology + a REMEDIATION PROBE: connect the agent to the
//      nodes' network and re-probe. If 000 flips to 401, `docker network
//      connect` is the fix and the next build can promote it into bring-up
//      proper (it cannot help THIS run — readiness already timed out).
//
// DIAGNOSTIC ONLY: set +e / exit 0, never fails the stage, stays cheap —
// it runs inside a catch while failFast may be tearing the build down.
def collectDockerDiagnostics() {
    sh '''
        set +e
        echo "===== docker diagnostics (cluster bring-up post-mortem) ====="
        docker ps -a --format "table {{.ID}}\t{{.Image}}\t{{.Status}}\t{{.Names}}" | head -20
        echo "----- host pressure -----"
        free -h 2>/dev/null
        df -h . 2>/dev/null
        docker stats --no-stream 2>/dev/null | head -10

        echo "----- network topology -----"
        docker network ls 2>/dev/null
        self="${HOSTNAME}"
        echo "agent container: $self"
        docker inspect -f "agent networks: {{range \\$k,\\$v := .NetworkSettings.Networks}}{{\\$k}}({{\\$v.IPAddress}}) {{end}}" "$self" 2>/dev/null

        # Select by container NAME, not image: cbdinocluster deploys by image
        # ID (deployOpts ImagePath sha256:...), so Config.Image is a bare sha
        # that never matches *couchbase* — verified live against a local
        # cbdinocluster v0.0.114 alloc. The controller names every node
        # container cbdynnode-<uuid>.
        for c in $(docker ps -q --filter "name=cbdynnode" 2>/dev/null); do
            ip=$(docker inspect -f "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}" "$c" 2>/dev/null | awk "{print \\$1}")
            name=$(docker inspect -f "{{.Name}}" "$c" 2>/dev/null)
            net=$(docker inspect -f "{{range \\$k,\\$v := .NetworkSettings.Networks}}{{\\$k}} {{end}}" "$c" 2>/dev/null | awk "{print \\$1}")
            echo "----- container $name ($c) ip=$ip net=$net -----"
            echo "from agent      8091: HTTP $(curl -m 5 -s -o /dev/null -w "%{http_code}" "http://$ip:8091/pools" 2>/dev/null)"
            echo "from agent     18091: HTTP $(curl -m 5 -k -s -o /dev/null -w "%{http_code}" "https://$ip:18091/pools" 2>/dev/null)"
            # Server-self probe: proves the REST port is up from inside the
            # node, independent of agent->node routing. Empty result = curl
            # absent from the image, not a failure.
            echo "from inside     8091: HTTP $(docker exec "$c" curl -m 5 -s -o /dev/null -w "%{http_code}" "http://localhost:8091/pools" 2>/dev/null)"
            echo "--- last 25 log lines ---"
            docker logs --tail 25 "$c" 2>&1 | tail -25
        done

        # Remediation probe — confirm the network-isolation hypothesis and
        # test its fix in one shot. Connecting the agent to the nodes' network
        # should flip the agent-side probe from 000 to 401. Leaves the agent
        # attached (harmless; stale links to removed networks self-clean).
        first=$(docker ps -q --filter "name=cbdynnode" 2>/dev/null | head -1)
        if [ -n "$first" ]; then
            net=$(docker inspect -f "{{range \\$k,\\$v := .NetworkSettings.Networks}}{{\\$k}} {{end}}" "$first" 2>/dev/null | awk "{print \\$1}")
            ip=$(docker inspect -f "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}" "$first" 2>/dev/null | awk "{print \\$1}")
            echo "----- remediation probe: docker network connect $net $self -----"
            docker network connect "$net" "$self" 2>&1
            echo "after connect,  8091: HTTP $(curl -m 5 -s -o /dev/null -w "%{http_code}" "http://$ip:8091/pools" 2>/dev/null) (000->401 means: promote this connect into bring-up)"
        fi
        exit 0
    '''
}


// Pin cbdinocluster on the current agent by invoking the cross-platform
// installer at cxx/scripts/install_cbdinocluster.py. The script reads the
// pinned version and per-asset SHA-256 map from cxx/scripts/cbdinocluster.json
// (bump-in-one-place), detects the agent's OS/arch, downloads from GitHub
// Releases, verifies SHA-256, and atomically installs to ~/bin or
// %USERPROFILE%\bin. Idempotent: a node that already has the pinned version
// short-circuits without re-downloading.
//
// PREREQUISITE: checkoutPipelineRepo() must have run on this node first —
// otherwise pipeline-scripts/ won't exist. The integration-test stages call
// both helpers in order; if you add a new caller, mirror that pattern.
//
// Python 3 (python3 on Unix, python on Windows) is a hard dependency —
// guarded by ensurePython() upstream of every caller.
def ensureCbdinocluster() {
    def script = 'pipeline-scripts/cxx/scripts/install_cbdinocluster.py'
    def config = 'pipeline-scripts/cxx/scripts/cbdinocluster.json'
    if (isUnix()) {
        sh "python3 ${script} --config ${config}"
        env.PATH = "${env.HOME}/bin:${env.PATH}"
    } else {
        powershell "python ${script} --config ${config}"
        env.PATH = "${env.USERPROFILE}\\bin;${env.PATH}"
    }
}


// Hard-gate Python 3 availability and print its version. The pipeline uses
// Python as its cross-platform shim for tasks that are awkward in *both* sh
// and PowerShell — currently the certificate JSON parsing in the integration
// test stages, and intended to expand (see ensureCbdinocluster Pythonization).
// Linux/macOS expose Python 3 as `python3` (system Python on RHEL/Rocky 9;
// Homebrew or system on macOS). Windows uses `python` — the official
// installer and `winget install Python.Python.3.x` put python.exe on PATH
// but do NOT create python3.exe, so we can't standardize on one name.
// Called from prepare-and-validate (fail-fast for the whole pipeline) and
// again from each integration-test node (different node-label resolutions
// may land on different physical agents).
def ensurePython() {
    if (isUnix()) {
        sh '''
            if ! command -v python3 >/dev/null 2>&1; then
                echo "ERROR: python3 is required but not on PATH." >&2
                echo "       Provision the agent: dnf install -y python3 (RHEL/Rocky), apt install -y python3 (Debian/Ubuntu), brew install python (macOS)." >&2
                exit 1
            fi
            python3 --version
        '''
    } else {
        powershell '''
            $ErrorActionPreference = 'Stop'
            if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
                throw "python is required but not on PATH. Provision the agent: winget install --exact --id Python.Python.3.12 --scope machine."
            }
            & python --version
        '''
    }
}


// Hard-gate the cmake version that will actually invoke configure on
// this node. The floor is the cxx-client's own cmake_minimum_required
// (3.19); since Packaging.cmake rewrites llhttp's inflated 3.25 floor
// down to 3.19 too, nothing in the tarball demands more (all other
// vendored deps declare <= 3.16). Note cmake < 3.26 still works but
// produces no CMakeConfigureLog.yaml (our configure-failure triage
// artifact). Failing fast at < 3.19 means a broken install surfaces
// here with a clean message naming the binary, rather than dying mid-
// FetchContent after grpc/curl/opentelemetry have all already downloaded.
//
// Called from prepare-and-validate (fail-fast for the whole pipeline,
// after ensurePython) and from each build-matrix node (inside withEnv,
// after the per-platform PATH manipulation, so the gate sees the cmake
// we'll actually invoke and not the agent's pre-prepend default).
def ensureCmake() {
    if (isUnix()) {
        sh '''
            set -e
            if ! command -v cmake >/dev/null 2>&1; then
                echo "ERROR: cmake not found on PATH" >&2
                echo "       PATH=$PATH" >&2
                exit 1
            fi
            version=$(cmake --version | head -1 | awk '{print $3}')
            major=$(echo "$version" | cut -d. -f1)
            minor=$(echo "$version" | cut -d. -f2)
            combined=$((major * 1000 + minor))
            if [ "$combined" -lt 3019 ]; then
                echo "ERROR: cmake $version at $(command -v cmake) is too old; need >= 3.19 (cxx-client's cmake_minimum_required)." >&2
                echo "       PATH=$PATH" >&2
                exit 1
            fi
            echo "cmake $version OK at $(command -v cmake)"
        '''
    } else {
        powershell '''
            $ErrorActionPreference = 'Stop'
            $cmd = Get-Command cmake -ErrorAction SilentlyContinue
            if (-not $cmd) { throw "cmake not found on PATH. PATH=$env:PATH" }
            $versionLine = (& cmake --version)[0]
            $version = [version]($versionLine -replace 'cmake version ','')
            if ($version -lt [version]"3.19") {
                throw "cmake $version at $($cmd.Source) is too old; need >= 3.19 (cxx-client's cmake_minimum_required). PATH=$env:PATH"
            }
            Write-Host "cmake $version OK at $($cmd.Source)"
        '''
    }
}


// Hard-gate workspace free space. Pure sh / powershell so this can
// run BEFORE ensurePython() if needed — preflight should not depend
// on Python being present. Always prints the measured number on
// success so capacity planning has data without ad-hoc
// instrumentation. On failure: non-zero exit with a clear message
// including node name, observed free GB, and threshold. Per-platform
// thresholds live in DISK_THRESHOLD_GB / INTEGRATION_DISK_THRESHOLD_GB
// at the top of the file. See cxx-pipeline-disk-defense-design.md §A.
//
// POSIX sh only — Jenkins runs sh steps under /bin/sh, which is dash
// on the qe-ubuntu24-* EC2 agents (build #9 died on `set -o pipefail`:
// "Illegal option") and busybox ash on alpine3.21. No pipefail; the
// numeric guard on free_kb catches a failed/garbled df more strictly
// than pipefail would (df -P pins the POSIX output format so column 4
// is dependable across distros).
def ensureDiskSpace(int minGB) {
    if (isUnix()) {
        sh """
            set -eu
            free_kb=\$(df -kP . | awk 'NR==2 {print \$4}')
            case "\$free_kb" in
                ''|*[!0-9]*)
                    echo "ERROR: could not determine free disk space on \${NODE_NAME:-?} (df output unparseable: '\$free_kb')" >&2
                    exit 1
                    ;;
            esac
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


stage("prepare and validate") {
    node(TARBALL_LABEL) {
        script {
            // .toBoolean() is load-bearing: these are Jenkins STRING params, and
            // an unchecked checkbox arrives as the string "false" — which Groovy
            // truthiness treats as TRUE (non-empty string). Without the
            // conversion every run gets named "-tls-cert" regardless of the
            // form values (builds #8–#12 were all mislabeled this way while
            // actually running plain).
            buildName([
                BUILD_NUMBER,
                PR_ID == "" ? null : "pr${PR_ID}",
                // STORAGE_BACKEND,
                USE_TLS.toBoolean() ? "tls" : null,
                USE_CERT_AUTH.toBoolean() ? "cert" : null,
            ].findAll { it != null }.join("-"))
        }
        cleanWs()

        stage("environment") {
            reportExecutingNode()
            // DIAGNOSTIC ONLY — `set +e` + `exit 0` deliberately swallows missing-tool
            // errors so the build log shows what *is* present rather than failing at the
            // first absent binary. Do not add load-bearing commands inside this heredoc;
            // gating belongs after it (see ensurePython() call below).
            sh '''
                set +e
                echo "===== OS / kernel ====="
                uname -a
                if [ -f /etc/os-release ]; then cat /etc/os-release; fi
                echo
                echo "===== CPU / memory / disk ====="
                echo "cores: $(nproc 2>/dev/null)"
                free -h 2>/dev/null
                df -h . 2>/dev/null
                echo
                echo "===== Toolchain versions ====="
                gcc --version       2>/dev/null | head -1
                g++ --version       2>/dev/null | head -1
                clang --version     2>/dev/null | head -1
                cmake --version     2>/dev/null | head -1
                ninja --version     2>/dev/null
                git --version       2>/dev/null
                python3 --version   2>/dev/null
                openssl version     2>/dev/null
                cbdinocluster version 2>/dev/null
                docker --version    2>/dev/null
                exit 0
            '''
            reportPersistence()
            // Hard-gates: pipeline assumes python3 from this point on, and
            // cmake >= 3.19 from the cmake-configure step at the bottom of
            // this stage. Failing here is cheaper than failing 30 minutes
            // later in cert handling or mid-FetchContent.
            ensurePython()
            ensureCmake()
        }

        checkout()

        stage("source tarball") {
            // Build the production source tarball via the cxx-client's own packaging_tarball
            // cmake target. The tarball is `git ls-files --recurse-submodules` plus a vendored
            // CPM third_party_cache (BoringSSL et al.), reproducibly archived. Distributing
            // this between stages — instead of the raw checkout — has two payoffs:
            //   1) the stash carries no .git, no IDE metadata, and no developer-state files,
            //      so we don't need an excludes list;
            //   2) every downstream build runs against exactly the artifact users will receive,
            //      which catches "tarball is missing a file" regressions in CI rather than in
            //      the wild.
            // The prepare-and-validate node is sdkqe-rockylinux9, whose dnf-installed cmake
            // (3.26.x) clears the cxx-client's cmake_minimum_required(3.19).
            dir("couchbase-cxx-client") {
                sh "cmake -B ./build -S . -DCOUCHBASE_CXX_CLIENT_INSTALL=ON"
                sh "cmake --build build --target packaging_tarball"
            }
            // Flatten the tarball path so the stash and downstream extraction are platform-trivial.
            sh "cp couchbase-cxx-client/build/packaging/couchbase-cxx-client-*.tar.gz tarball.tar.gz"
        }

        // Stash uses a stable name so each build node can unstash "tarball"
        // without knowing the version. The archive keeps the versioned
        // filename customers see in releases, so the Jenkins UI download
        // is byte-identical to the published source tarball.
        stash includes: "tarball.tar.gz", name: "tarball"
        archiveArtifacts artifacts: "couchbase-cxx-client/build/packaging/couchbase-cxx-client-*.tar.gz", fingerprint: true
    }
}


// Named "build matrix" (not "build") so it can't collide with the
// per-platform "build" stage nested inside each parallel lane below —
// a stage sharing a name with its own descendant confuses Blue Ocean
// and the Stage View plugin (both key visuals by stage name). The
// prep / build / unit-tests names repeating across *sibling* lanes is
// fine: lanes are namespaced by their branch (platform) name.
stage("build matrix") {
    def builds = [:]
    for (p in PLATFORMS) {
        def platform = p
        builds[platform] = {
            node(PLATFORM_EXECUTOR[platform]) {
                // Per-platform timeout: a single hung node (network stall on a CPM fetch,
                // runaway compile, deadlocked test agent) shouldn't starve the rest of the
                // matrix of the outer 60-min budget. 45 min is comfortably above observed
                // worst-case (rocky9 cold build ~18 min, msvc-2022 ~25 min) without being
                // generous enough to hide a real hang.
                timeout(unit: 'MINUTES', time: 45) {
                stage("prep") {
                    reportExecutingNode()
                    // Before the disk gate on purpose: if the gate trips, the probe's
                    // ccache-size / marker output is exactly the context that explains
                    // *why* the agent is full.
                    reportPersistence()

                    // Pre-flight disk gate. Per-platform threshold from DISK_THRESHOLD_GB
                    // (win2022-amd64: 40 GB for gRPC+protobuf+boringssl PDB output; others: 15 GB).
                    // Fails in ~5 s if the agent is full instead of after ~20 min of compile
                    // work — see cxx-pipeline-disk-defense-design.md §1.
                    ensureDiskSpace(DISK_THRESHOLD_GB[platform])

                    // Per-node toolchain report: what THIS build node actually has, not just what
                    // prepare-and-validate saw. Best-effort — missing tools never fail the stage.
                    // Goal: when a build breaks on one platform, the build log already records the
                    // exact gcc/clang/cmake (and cl.exe on Windows, where reachable) it ran with,
                    // so triage doesn't require agent-image archaeology.
                    if (platform == "win2022-amd64") {
                        powershell '''
                            Write-Host "===== OS / kernel ====="
                            [System.Environment]::OSVersion.ToString()
                            "machine $([System.Environment]::MachineName) ($([System.Environment]::ProcessorCount) cpus)"
                            Write-Host ""
                            Write-Host "===== Toolchain versions ====="
                            & cmake --version 2>&1 | Select-Object -First 1
                            & git --version 2>&1
                            "PowerShell $($PSVersionTable.PSVersion)"
                            "cores: $env:NUMBER_OF_PROCESSORS"
                            $py = Get-Command python -ErrorAction SilentlyContinue
                            if ($py) {
                                "python at $($py.Source)"
                                & python --version 2>&1
                            } else {
                                "python not on PATH (required once integration tests run on Windows; see ensurePython)"
                            }
                            $cl = Get-Command cl.exe -ErrorAction SilentlyContinue
                            if ($cl) {
                                "cl.exe at $($cl.Source)"
                                & cl.exe 2>&1 | Select-Object -First 1
                            } else {
                                "cl.exe not on PATH (cmake's VS generator sets it up at configure time)"
                            }
                        '''
                    } else {
                        // DIAGNOSTIC ONLY — same convention as the prepare-and-validate
                        // env stage: best-effort dump, never gates. Hard requirements
                        // (e.g. ensurePython()) belong outside this heredoc.
                        sh '''
                            set +e
                            echo "===== OS / kernel ====="
                            uname -a
                            if [ -f /etc/os-release ]; then cat /etc/os-release; fi
                            if command -v sw_vers >/dev/null 2>&1; then sw_vers; fi
                            echo
                            echo "===== Toolchain versions ====="
                            gcc --version       2>/dev/null | head -1
                            g++ --version       2>/dev/null | head -1
                            clang --version     2>/dev/null | head -1
                            cmake --version     2>/dev/null | head -1
                            ninja --version     2>/dev/null
                            git --version       2>/dev/null
                            make --version      2>/dev/null | head -1
                            python3 --version   2>/dev/null
                            echo "cores: $(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null)"
                            exit 0
                        '''
                    }
                    dir("ws_${platform}") {
                        deleteDir()
                        unstash "tarball"
                        // Extract the production tarball into couchbase-cxx-client/, stripping
                        // the versioned top-level directory (couchbase-cxx-client-${SEMVER}/).
                        // Windows 10/11 and Server 2019+ ship bsdtar at C:\Windows\System32\tar.exe,
                        // which handles -xzf and --strip-components identically to GNU tar.
                        if (platform == "win2022-amd64") {
                            powershell '''
                                $ErrorActionPreference = 'Stop'
                                New-Item -ItemType Directory -Force -Path couchbase-cxx-client | Out-Null
                                tar -xzf tarball.tar.gz -C couchbase-cxx-client --strip-components=1
                                if ($LASTEXITCODE -ne 0) { throw "tar failed ($LASTEXITCODE)" }
                                Remove-Item tarball.tar.gz
                            '''
                        } else {
                            sh """
                                mkdir couchbase-cxx-client
                                tar -xzf tarball.tar.gz -C couchbase-cxx-client --strip-components=1
                                rm tarball.tar.gz
                            """
                        }
                    }
                }
                stage("build") {
                    // Per-agent parallelism via computeJobs() (cores - 2, min 1). Replaces
                    // the previous CB_NUMBER_OF_JOBS=4 hardcode that capped every platform
                    // at 4-wide regardless of agent size. Self-reports cores detected +
                    // jobs chosen to the build log. See cxx-pipeline-disk-defense-design.md
                    // §2 + §C.
                    def envs = ["CB_NUMBER_OF_JOBS=${computeJobs()}"]
                    // Raise ccache's trim ceiling from the 5 GB default: one cold
                    // Debug build is ~1 GB (build #12 probe: 3015 objects / 938M),
                    // so the default holds only ~4-5 builds before LRU eviction
                    // starts eating exactly the warm objects we want across
                    // builds. Runners are persistent (pid1_age 15+ days, cache
                    // survived the #11→#12 boundary) and the prep disk gate
                    // guarantees 15 GB free before we start, so 20 GB is safe.
                    // Env var rather than `ccache --set-config`: no agent state
                    // mutated, the value is versioned here, and it only governs
                    // compiles in this stage. Harmless on win2022 (no ccache) —
                    // cmake/Cache.cmake just never finds the binary.
                    envs.push("CCACHE_MAXSIZE=20G")
                    if (platform == "macos14-amd64") {
                        envs.push("OPENSSL_ROOT_DIR=/usr/local/opt/openssl")
                    } else if (platform == "macos15-arm64") {
                        envs.push("OPENSSL_ROOT_DIR=/opt/homebrew/opt/openssl")
                    } else if (platform == "rocky9-amd64") {
                        envs.push("CB_CC=gcc")
                        envs.push("CB_CXX=g++")
                    }
                    def path = PATH
                    if (platform == "win2022-amd64") {
                        // BoringSSL is statically linked (-DCOUCHBASE_CXX_CLIENT_STATIC_BORINGSSL=ON),
                        // so no separate OpenSSL install is required on Windows.
                        // TODO(TD-04): verify on the msvc-2022 agent that (a) winget is available
                        //              and (b) bypassing ./bin/build-tests in favor of a direct cmake
                        //              invocation still produces what the unit/integration test stages
                        //              expect under cmake-build-tests/. If winget isn't on the AMI,
                        //              fall back to choco or a direct download of the official archive
                        //              from cmake.org.
                        powershell """
                            \$ErrorActionPreference = 'Stop'
                            \$installed = \$false
                            if (Get-Command cmake -ErrorAction SilentlyContinue) {
                                if ((& cmake --version 2>\$null) -match 'version ${CMAKE_VERSION}') {
                                    \$installed = \$true
                                }
                            }
                            if (-not \$installed) {
                                winget install --exact --id Kitware.CMake --version ${CMAKE_VERSION} --silent --accept-package-agreements --accept-source-agreements --scope machine --disable-interactivity
                                if (\$LASTEXITCODE -ne 0) { throw "winget exit \$LASTEXITCODE" }
                            }
                        """
                        path = "C:\\Program Files\\CMake\\bin;" + path
                    } else if (platform == "macos14-amd64" || platform == "macos15-arm64") {
                        // macOS uses Homebrew's cmake. Fail loud if it's not installed — agents
                        // should be provisioned ahead of the build with `brew install cmake`, and
                        // a missing dep ought to abort here rather than be hidden by an
                        // opportunistic install.
                        sh '''
                            if ! brew list --versions cmake >/dev/null 2>&1; then
                                echo "ERROR: cmake is not installed via Homebrew on this agent." >&2
                                echo "       Provision the agent with: brew install cmake" >&2
                                exit 1
                            fi
                            echo "Using Homebrew cmake: $(brew list --versions cmake)"
                        '''
                        // Apple Silicon: /opt/homebrew/bin; Intel macOS: /usr/local/bin.
                        def brewBin = (platform == "macos15-arm64") ? "/opt/homebrew/bin" : "/usr/local/bin"
                        path = "${brewBin}:" + path
                    } else {
                        // Linux platforms — rockylinux9, alpine3.21, qe-rhel9-arm64, qe-ubuntu24-* —
                        // ship cmake ≥ 3.26 via dnf/apt/apk (well above cxx-client's
                        // cmake_minimum_required(3.19), which is also the tarball's floor after
                        // Packaging.cmake rewrites llhttp's inflated 3.25). Some agents have a
                        // stale /usr/local/bin/cmake installed manually that shadows the distro
                        // package; prepend /usr/bin so the distro cmake wins without needing
                        // sudo to remove the manual install. NOTE: this is best-effort only —
                        // build #8 showed qe-ubuntu24-arm64 re-imaged WITHOUT the apt cmake, so
                        // /usr/bin/cmake didn't exist and the stale 3.21.4 was still resolved;
                        // ensureCmake() below is the actual gate.
                        path = "/usr/bin:" + path
                    }
                    echo("PATH=$path")
                    envs.push("PATH=$path")
                    withEnv(envs) {
                        // Inside withEnv so the gate sees the per-platform-adjusted PATH
                        // (winget-installed cmake on Windows, brew on macOS, /usr/bin on
                        // Linux), not the agent's pre-prepend default.
                        ensureCmake()
                        try {
                            dir("ws_${platform}/couchbase-cxx-client") {
                                if (platform == "win2022-amd64") {
                                    powershell '''
                                        cmake --version
                                        if ($LASTEXITCODE -ne 0) { throw "cmake --version failed ($LASTEXITCODE)" }
                                        git --version
                                        if ($LASTEXITCODE -ne 0) { throw "git --version failed ($LASTEXITCODE)" }
                                    '''
                                    dir("build") {
                                        powershell 'cmake -S .. -B . -DCOUCHBASE_CXX_CLIENT_STATIC_BORINGSSL=ON -DCMAKE_SYSTEM_VERSION=10.0.20348.0'
                                        powershell 'cmake --build . --parallel $env:CB_NUMBER_OF_JOBS'
                                    }
                                } else {
                                    sh("./bin/build-tests")
                                }
                            }
                        } catch (buildErr) {
                            // cmake >= 3.26 writes an aggregated structured configure log to
                            // CMakeFiles/CMakeConfigureLog.yaml — supersedes CMakeError.log +
                            // CMakeOutput.log + CMakeCache.txt as a triage artifact. Linux/macOS
                            // build dir is cmake-build-tests/, msvc-2022 is build/; ** picks up
                            // the top-level YAML plus one per FetchContent sub-build, which is
                            // exactly what you want when configure fails mid-sub-project.
                            // allowEmptyArchive keeps a failed-but-produced-no-logs case (e.g.
                            // failFast-induced interrupt before cmake ran) from itself throwing
                            // and clobbering buildErr.
                            archiveArtifacts(
                                artifacts: "ws_${platform}/couchbase-cxx-client/cmake-build-tests/**/CMakeConfigureLog.yaml,ws_${platform}/couchbase-cxx-client/build/**/CMakeConfigureLog.yaml",
                                allowEmptyArchive: true,
                                onlyIfSuccessful: false
                            )
                            throw buildErr
                        }
                    }
                    // Drop build intermediates on every platform (frees agent disk now,
                    // and pre-slims the stashes for the planned all-platform integration
                    // matrix). Only reached on build success — the catch above rethrows,
                    // and the CMakeConfigureLog.yaml it archives lives in CMakeFiles/,
                    // which only gets pruned here on the success path.
                    dir("ws_${platform}/couchbase-cxx-client") {
                        pruneForStash()
                    }
                    if (platform == COMBINATION_PLATFORM) {
                        // The workspace was hydrated from a clean tarball (see "source tarball"
                        // sub-stage in prepare-and-validate), so default excludes are safe — no
                        // .git, no IDE metadata, no stray dotfiles to strip out.
                        // Payload is pruned above: ~2.5 GB of load-bearing binaries instead
                        // of the 7.7 GB full workspace that took ~8 min to stash.
                        stash(includes: "ws_${platform}/", name: "${platform}_build")
                    }
                }
                }
            }
        }
    }
    // failFast aborts in-flight platforms as soon as one fails, freeing those agents
    // for the next build instead of letting them grind to completion. The outer 60-min
    // is the upper bound for the whole matrix; the per-platform 45-min above is the
    // upper bound for any single node. If both fire, killing the work surfaces the
    // problem rather than burying it in agent-hours.
    builds.failFast = true
    timeout(unit: 'MINUTES', time: 60) {
        parallel(builds)
    }
}


class DynamicCluster {
    String id_ = null
    String version_ = null
    boolean useTLS = false
    boolean useCertAuth = false
    String certsDir = null
    String connstr = null
    String firstIp = null

    DynamicCluster(String version) {
        this.version_ = version
    }

    String clusterId() {
        return id_
    }

    // The connection string is populated once at allocate-time from
    // `cbdinocluster connstr [--tls|--no-tls]`, with the trust certificate
    // appended for TLS. Callers don't need to compose it from parts.
    String connectionString() {
        return connstr
    }

    String firstIP() {
        return firstIp
    }

    String certPath() {
        if (useCertAuth) {
            return "$certsDir/client.pem"
        } else {
            return ""
        }
    }

    String keyPath() {
        if (useCertAuth) {
            return "$certsDir/client.key"
        } else {
            return ""
        }
    }
}


if (!SKIP_TESTS.toBoolean()) {
    node("sdkqe-${PLATFORM_EXECUTOR[COMBINATION_PLATFORM]}") {
        timeout(unit: 'MINUTES', time: 10) {
            stage("unit tests") {
                reportExecutingNode()
                unstash("${COMBINATION_PLATFORM}_build")
                withEnv([
                    "CTEST_OUTPUT_ON_FAILURE=1",
                    "TEST_LOG_LEVEL=trace"
                ]) {
                    dir("ws_${COMBINATION_PLATFORM}/couchbase-cxx-client") {
                        try {
                            sh("./bin/run-unit-tests")
                        } finally {
                            // allowEmptyResults: if the test runner crashed before writing
                            // results.xml, junit's own "no test reports found" failure would
                            // otherwise mask the real cause (the original sh() throw).
                            junit(testResults: "cmake-build-tests/results.xml", allowEmptyResults: true)
                        }
                    }
                }
            }
        }
    }

    stage("integration tests: tls=${USE_TLS}, cert_auth=${USE_CERT_AUTH}") {
        def cbverStages = [:]
        CB_VERSIONS.each { cb_version ->
            def v = cb_version.value
            // CE images stop earlier than EE in every train, so the Community
            // run pins its own tag (the yaml below prefixes "community-").
            def version = USE_CE.toBoolean() ? v["ceTag"] : v["tag"]
            def label = version
            if (v["label"] != null) {
                label = v["label"]
            }
            cbverStages["${COMBINATION_PLATFORM}-${label}"] = {
                // cbdinocluster is node-local: it stores allocated-cluster state on the agent's
                // filesystem, and docker-deployed cluster containers live on the agent's docker
                // daemon (private bridge network, not reachable from other agents). Keep the
                // bring-up / test / cleanup trio inside this single node() block — splitting it
                // across nodes leaks the cluster (rm has no record of it) and breaks TCP reach
                // from the test stage to the cluster.
                node("sdkqe-${PLATFORM_EXECUTOR[COMBINATION_PLATFORM]}") {
                    def CLUSTER = new DynamicCluster(version)
                    // Per-job docker bridge for agent<->node reachability (build #16 fix).
                    // Unique per build+lane so concurrent co-tenant lanes that land on the
                    // same swarm host never collide on this host-global network name.
                    // Created in the bring-up below, attached to this agent, handed to
                    // cbdinocluster via --prefer, and torn down in the cleanup stage.
                    def DOCKER_NET = "cxxcbc-${BUILD_NUMBER}-${COMBINATION_PLATFORM}-${label}".replaceAll(/[^A-Za-z0-9_.-]/, '-')
                    // Per-job cbdinocluster config. v0.0.115+ resolves
                    // CBDINOCLUSTER_CONFIG (env) ahead of the default
                    // ~/.cbdinocluster, so every cbdinocluster call in the bring-up
                    // and cleanup below reads/writes a workspace-local file instead
                    // of the shared home-dir config that co-tenant SDK jobs on this
                    // swarm agent also use. That isolation is what lets
                    // generate_cbdinocluster_config.py rewrite the docker network
                    // (build #13 fix) without clobbering anyone else's config.
                    withEnv(["CBDINOCLUSTER_CONFIG=${env.WORKSPACE}/.cbdinocluster-cxx"]) {
                    try {
                        stage(label) {
                            reportExecutingNode()
                            reportPersistence()
                            // 25-min cap on the cluster bring-up: docker version, ensure*,
                            // cbdinocluster init/alloc/buckets-add/cert-fetch/connstr, and the
                            // N1QL primary-index curl. A wedged `cbdinocluster alloc` or hung
                            // docker daemon must not eat into the 40-min test budget below —
                            // keeping them as independent timeouts makes the failure mode
                            // (bring-up hang vs test hang) immediately legible in the build log.
                            // Was 15 min, calibrated before allocs really ran: build #12 spent
                            // ~2 min pulling the server image on a cold agent and the 3-node
                            // ready-wait was still in flight when the cap killed it (the alloc
                            // itself was healthy — containers up at T+95s).
                            //
                            // On any bring-up failure (incl. this timeout firing) the catch
                            // below dumps docker-side state — container status, host memory,
                            // last server log lines, and 8091/18091 management-port probes —
                            // because `alloc -v` is silent during the ready-wait and the
                            // cbdinocluster log alone cannot distinguish slow-boot from
                            // OOM-loop from wedged.
                            try {
                            timeout(unit: 'MINUTES', time: 25) {
                            deleteDir()
                            // Pre-flight disk gate. INTEGRATION_DISK_THRESHOLD_GB covers
                            // 3× CB server Docker images (~1.5 GB each) + cluster runtime
                            // data + build artifact unstash. Runs after deleteDir() so the
                            // prior workspace has been freed, before checkoutPipelineRepo()
                            // adds the script clone. See cxx-pipeline-disk-defense-design.md §3.
                            ensureDiskSpace(INTEGRATION_DISK_THRESHOLD_GB)
                            // Clone the Jenkinsfile's repo into pipeline-scripts/ so the
                            // installer and extract_cert.py scripts under cxx/scripts/ are
                            // reachable. Must precede ensureCbdinocluster() and any cert
                            // extraction below.
                            checkoutPipelineRepo()

                            // Record the runner-local docker version, hard-gate Python (cert
                            // parsing uses it), and ensure cbdinocluster is present at the pinned
                            // version (downloads + verifies SHA-256 if not). Cluster lifecycle is
                            // owned by docker + cbdinocluster, so a flake here usually points at
                            // one of them — the version lines in the build log narrow it down
                            // immediately instead of leaving you guessing across agent images.
                            sh("docker --version")
                            ensurePython()
                            ensureCbdinocluster()

                            // cbdinocluster topology and cert mode are declared up-front in YAML
                            // and consumed by `cbdinocluster alloc --def-file=...`. There's no
                            // post-alloc `setup` step the way cbdyncluster had.
                            def edition = USE_CE.toBoolean() ? "community-" : ""
                            def fullVersion = "${edition}${version}"
                            def primaryServices  = USE_CE.toBoolean() ? "[kv, index, n1ql, fts]" : "[kv, index, n1ql]"
                            def tertiaryServices = USE_CE.toBoolean() ? "[kv]"                  : "[kv, fts, cbas, eventing]"
                            CLUSTER.useTLS      = USE_TLS.toBoolean()
                            CLUSTER.useCertAuth = USE_CERT_AUTH.toBoolean()
                            CLUSTER.certsDir    = WORKSPACE
                            def useDinoCerts = CLUSTER.useTLS && CLUSTER.useCertAuth
                            writeFile file: "cluster.yaml", text: """\
nodes:
  - count: 1
    version: ${fullVersion}
    services: ${primaryServices}
  - count: 1
    version: ${fullVersion}
    services: [kv]
  - count: 1
    version: ${fullVersion}
    services: ${tertiaryServices}
docker:
  kv-memory: 2048
  fts-memory: 2048
  cbas-memory: 2048
  use-dino-certs: ${useDinoCerts}
expiry: 2h
"""
                            // expiry 2h (was 4h): the worst honest lifetime is bring-up cap
                            // (25 min) + test cap (40 min) + slack. Expiry is the ONLY thing
                            // that reclaims a cluster whose alloc was interrupted before
                            // printing its id (build #12 leaked 3 containers exactly this
                            // way — SIGTERM mid-ready-wait, id never captured, rm skipped),
                            // so a tight value directly bounds how long a leak squats on
                            // the agent's RAM.

                            // Surface the generated def in the build log: alloc failures
                            // (e.g. build #11's unparseable version aliases) reference the
                            // YAML fields, so triage should not require reconstructing the
                            // file from groovy interpolation by hand.
                            sh("cat cluster.yaml")

                            // The agent's only shared network on these swarm hosts is the
                            // non-attachable overlay sdkqe_jenkins, which standalone
                            // cbdinocluster node containers cannot join — so build #16 hit
                            // the generator's fail-fast gate (no usable shared network) and
                            // the cluster never came up. Manufacture the missing network:
                            // a per-job user-defined bridge (embedded DNS, score 100) that
                            // both this agent and the cluster nodes attach to.
                            //
                            // Reaper first: rm any cxxcbc-* bridge a crashed earlier build
                            // orphaned. `network rm` only removes a bridge with no live
                            // endpoints, so a concurrent lane's in-use network is skipped
                            // harmlessly. Then force-clear our exact name (a same-named
                            // bridge from a prior run on this host may still hold our agent
                            // endpoint) so the create is idempotent.
                            sh("""
                                set -eu
                                for n in \$(docker network ls --filter name=cxxcbc- --format '{{.Name}}'); do
                                    docker network rm "\$n" 2>/dev/null || true
                                done
                                docker network disconnect -f ${DOCKER_NET} "\$(hostname)" 2>/dev/null || true
                                docker network rm ${DOCKER_NET} 2>/dev/null || true
                                docker network create --driver bridge ${DOCKER_NET} >/dev/null
                                docker network connect ${DOCKER_NET} "\$(hostname)"
                                echo "created per-job docker bridge ${DOCKER_NET} and attached agent \$(hostname)"
                            """)
                            // Generate the per-job cbdinocluster config (written to
                            // $CBDINOCLUSTER_CONFIG) instead of `cbdinocluster init --auto`.
                            // --prefer pins docker.network to the bridge created above; the
                            // generator still validates the agent is attached to it and
                            // fails fast otherwise. It copies the shared ~/.cbdinocluster,
                            // disables the Capella deployer (unmanaged creds → cleanup
                            // FATAL), and prints the docker network inventory it decided
                            // from BEFORE writing.
                            sh("""
                                python3 pipeline-scripts/cxx/scripts/generate_cbdinocluster_config.py \\
                                    --source "\$HOME/.cbdinocluster" \\
                                    --output "\$CBDINOCLUSTER_CONFIG" \\
                                    --agent-container "\$(hostname)" \\
                                    --prefer ${DOCKER_NET}
                            """)
                            // Reap anything past its expiry that earlier builds left behind
                            // (interrupted allocs can't be rm'd by their own cleanup stage —
                            // no id). Runs before OUR alloc so the leaked clusters' RAM and
                            // disk are back in the pool when the new nodes boot. Scoped to
                            // expired resources only, so concurrent jobs' live clusters on
                            // shared sdkqe agents are untouched. Best-effort by design.
                            sh("cbdinocluster cleanup || true")
                            CLUSTER.id_ = sh(script: "cbdinocluster -v alloc --def-file=cluster.yaml", returnStdout: true).trim()
                            // Guard against the alloc returning empty/garbage: every downstream
                            // step interpolates ${CLUSTER.clusterId()} into a shell command, and
                            // the cleanup `cbdinocluster rm ""` would mask a real allocation
                            // failure with a no-op (or worse, a leaked cluster if some future
                            // cbdinocluster CLI emits a banner on stdout before the id).
                            if (!(CLUSTER.clusterId() ==~ /[A-Za-z0-9_-]+/)) {
                                error("cbdinocluster alloc returned an empty/invalid cluster id: '${CLUSTER.clusterId()}'")
                            }

                            // TODO(TD-07): cbdinocluster does not currently expose
                            //              --storage-backend on bucket creation; the docker
                            //              deployer hardcodes "couchstore"
                            //              (deployment/commondeploy/{agent,mgmtx}helper.go). The
                            //              previously-honored STORAGE_BACKEND job parameter is now
                            //              silently ignored. If magma testing matters here, add
                            //              the flag upstream in cbdinocluster, or fall back to a
                            //              direct REST call against
                            //              ${CLUSTER.firstIP()}:8091/pools/default/buckets.
                            sh("cbdinocluster buckets add ${CLUSTER.clusterId()} default --ram-quota-mb 256")

                            if (CLUSTER.useCertAuth) {
                                // Client cert auth uses cbdinocluster's global "dino" CA, which
                                // signs the per-user client cert returned by get-client-cert.
                                // The cluster trusts that CA because use-dino-certs:true is set
                                // in the def above. --json (cbdinocluster >= v0.0.114) returns
                                // {cert, key} as structured fields; extract_cert.py reads stdin
                                // and writes the requested files. get-client-cert mints a fresh
                                // keypair on every call, so a single piped invocation (one stdin
                                // read → two files) is the only way to get a matching pair.
                                //
                                // pipefail is enabled only where /bin/sh supports it (bash; dash
                                // on qe-ubuntu24-* rejects it — build #9). Where it's off, a
                                // failed cbdinocluster still surfaces: extract_cert.py exits
                                // non-zero on empty/non-JSON stdin. Don't capture the JSON in a
                                // shell variable instead — Jenkins runs sh steps with -x, which
                                // would trace the client key material into the build log.
                                sh """
                                    set -eu
                                    if (set -o pipefail) 2>/dev/null; then set -o pipefail; fi
                                    cbdinocluster certificates get-dino-ca --json \\
                                        | python3 pipeline-scripts/cxx/scripts/extract_cert.py \\
                                            --cert-out ${CLUSTER.certsDir}/ca.pem
                                    cbdinocluster certificates get-client-cert --json Administrator \\
                                        | python3 pipeline-scripts/cxx/scripts/extract_cert.py \\
                                            --cert-out ${CLUSTER.certsDir}/client.pem \\
                                            --key-out  ${CLUSTER.certsDir}/client.key
                                """
                            } else if (CLUSTER.useTLS) {
                                sh """
                                    set -eu
                                    if (set -o pipefail) 2>/dev/null; then set -o pipefail; fi
                                    cbdinocluster certificates get-ca --json ${CLUSTER.clusterId()} \\
                                        | python3 pipeline-scripts/cxx/scripts/extract_cert.py \\
                                            --cert-out ${CLUSTER.certsDir}/ca.pem
                                """
                            }

                            CLUSTER.firstIp = sh(script: "cbdinocluster ip ${CLUSTER.clusterId()}", returnStdout: true).trim()
                            def connFlag = CLUSTER.useTLS ? "--tls" : "--no-tls"
                            def rawConnstr = sh(script: "cbdinocluster connstr ${connFlag} ${CLUSTER.clusterId()}", returnStdout: true).trim()
                            if (CLUSTER.useTLS) {
                                rawConnstr += "?trust_certificate=${CLUSTER.certsDir}/ca.pem"
                            }
                            CLUSTER.connstr = rawConnstr

                            // TODO(TD-08): replace the placeholder `cxx-ci-cbdinocluster-admin`
                            //              credentialsId with the actual Jenkins credential
                            //              configured in the SDK build folder. Until then this
                            //              stage will fail at withCredentials with a clear
                            //              "credential not found" — that's the intended
                            //              fail-fast behavior, not a regression. Reuse the
                            //              `Administrator` / `password` pair currently baked
                            //              into cbdinocluster's docker deployer image.
                            //
                            // Why withCredentials + single-quoted sh body:
                            //   - Jenkins binds the secret to $CB_USER / $CB_PASS env vars and
                            //     masks them in the console log; a Groovy-interpolated literal
                            //     would NOT be masked because Jenkins doesn't know the value.
                            //   - Single-quoted sh body (Groovy `'''...'''`) prevents Groovy
                            //     from rendering ${CB_PASS} into the script string before sh
                            //     ever sees it — without this, the password lands in the
                            //     Jenkins pipeline log via the `sh -x` echo of the rendered
                            //     command.
                            //   - --fail-with-body (curl ≥ 7.76) makes HTTP 4xx/5xx exit
                            //     non-zero while still printing the response body, so a
                            //     silent index-create no-op surfaces as a build failure.
                            withCredentials([usernamePassword(credentialsId: 'cxx-ci-cbdinocluster-admin', usernameVariable: 'CB_USER', passwordVariable: 'CB_PASS')]) {
                                withEnv(["CB_QUERY_URL=http://${CLUSTER.firstIP()}:8093/query/service"]) {
                                    sh '''
                                        curl --fail-with-body -sS -u "$CB_USER:$CB_PASS" "$CB_QUERY_URL" \
                                            -d 'statement=CREATE PRIMARY INDEX ON default USING GSI' \
                                            -d 'timeout=300s'
                                    '''
                                }
                            }
                            }  // close cluster bring-up timeout
                            } catch (bringupErr) {
                                // Post-mortem before the exception propagates (and before
                                // failFast reuses/tears down the agent). Best-effort — the
                                // helper never throws.
                                collectDockerDiagnostics()
                                throw bringupErr
                            }
                        }
                        timeout(unit: 'MINUTES', time: 40) {
                            stage("test") {
                                unstash("${COMBINATION_PLATFORM}_build")
                                withEnv([
                                    "TEST_CONNECTION_STRING=${CLUSTER.connectionString()}",
                                    "CTEST_OUTPUT_ON_FAILURE=1",
                                    "TEST_LOG_LEVEL=trace",
                                    "TEST_USE_WAN_DEVELOPMENT_PROFILE=yes",
                                    "TEST_CERTIFICATE_PATH=${CLUSTER.certPath()}",
                                    "TEST_KEY_PATH=${CLUSTER.keyPath()}"
                                ]) {
                                    dir("ws_${COMBINATION_PLATFORM}/couchbase-cxx-client") {
                                        withEnv([
                                            "CB_STRICT_ENCRYPTION=${USE_TLS}",
                                            "CB_HOST=${CLUSTER.firstIP()}",
                                            "CB_TRAVEL_SAMPLE=true",
                                            "CB_FTS_QUOTA=2048", // See MB-64303
                                        ]) {
                                            // Fail loudly if init-cluster is absent rather than silently
                                            // running tests against an uninitialized cluster. The script
                                            // is part of the cxx-client tarball; missing-from-tarball is
                                            // a packaging regression that should stop the build, not a
                                            // "skip and hope" branch. If a future cxx-client version
                                            // intentionally drops this script, add an explicit
                                            // version-gated branch here.
                                            sh('''
                                                if [ ! -f ./bin/init-cluster ]; then
                                                    echo "ERROR: ./bin/init-cluster missing from tarball — refusing to run tests against an uninitialized cluster." >&2
                                                    exit 1
                                                fi
                                                ./bin/init-cluster
                                            ''')
                                        }
                                        try {
                                            sh("./bin/run-integration-tests")
                                        } catch (e) {
                                            // Best-effort log collection: a failure here (cbdinocluster
                                            // collect-logs throwing, archive step error, disk full)
                                            // must NOT replace the original test exception `e` — that's
                                            // the one engineers need to see. Wrap each step in its own
                                            // try/catch and log-only on failure.
                                            try {
                                                sh("mkdir -p server_logs_${label} && cbdinocluster collect-logs ${CLUSTER.clusterId()} server_logs_${label}")
                                            } catch (logErr) {
                                                echo("log collection failed (suppressed so original test exception propagates): ${logErr}")
                                            }
                                            try {
                                                archiveArtifacts(artifacts: "server_logs_${label}/*.zip", allowEmptyArchive: true)
                                            } catch (archErr) {
                                                echo("artifact archival failed (suppressed): ${archErr}")
                                            }
                                            throw e
                                        } finally {
                                            junit(testResults: "cmake-build-tests/results.xml", allowEmptyResults: true)
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        stage("cleanup") {
                            try {
                                // Guard the rm: alloc may have failed before CLUSTER.id_ was set, in
                                // which case `cbdinocluster rm ""` would either error and mask the
                                // real failure, or no-op silently. The guard mirrors the alloc
                                // validation above and keeps the original exception intact.
                                if (CLUSTER.clusterId()) {
                                    sh("cbdinocluster rm ${CLUSTER.clusterId()}")
                                } else {
                                    echo("skipping cbdinocluster rm: cluster id was never set")
                                    // The id-less case is exactly the one that leaks (build #12:
                                    // alloc SIGTERM'd mid-ready-wait left 3 running containers).
                                    // We can't rm what we can't name, but we can reap whatever
                                    // is already past expiry — ours from a previous round, or
                                    // older builds'. Best-effort: never mask the original error.
                                    sh("cbdinocluster cleanup || true")
                                }
                            } finally {
                                // Tear down the per-job docker bridge created for agent<->node
                                // reachability. In a finally so it runs even when the rm above
                                // throws (rm is intentionally not ||true — its failures must stay
                                // visible). Unconditional: the bridge can exist even when alloc
                                // never ran (gate/connect failure), and a leaked docker network is
                                // invisible to cbdinocluster's expiry reaper, so nothing else will
                                // ever remove it. The cluster nodes — the bridge's other endpoints
                                // — were removed just above, leaving the agent as the last
                                // endpoint; force-disconnect it, then rm. Every step ||true: this
                                // is teardown, it must never replace the failure that sent us here.
                                sh("""
                                    docker network disconnect -f ${DOCKER_NET} "\$(hostname)" 2>/dev/null || true
                                    docker network rm ${DOCKER_NET} 2>/dev/null || true
                                """)
                            }
                        }
                    }
                    }  // withEnv CBDINOCLUSTER_CONFIG
                }
            }
        }
        // TODO(TD-09): flip to true once the Capella credentials are provisioned
        // on the sdkqe agents (see the withCredentials TODO inside the stage).
        // Until then `init --auto` cannot configure the cloud deployer (build #11:
        // IMDS timeouts, "Capella: Enabled: false"), the branch fails every run,
        // and cbverStages.failFast lets that abort the healthy docker branches.
        def CAPELLA_READY = false
        if (CAPELLA_READY) {
        cbverStages["${COMBINATION_PLATFORM}-capella"] = {
            // Same single-node() constraint as the docker branch above: cbdinocluster's
            // bookkeeping (which deployer owns this cluster id, what config was used) is
            // stored on the agent's local filesystem even when the cluster itself is in
            // the cloud. Allocating on one agent and running `cbdinocluster rm` on another
            // leaks the cluster — and a leaked Capella cluster bills the org until expiry.
            node("sdkqe-${PLATFORM_EXECUTOR[COMBINATION_PLATFORM]}") {
                def CLUSTER = new DynamicCluster("capella")
                try {
                    stage("capella") {
                        reportExecutingNode()
                        reportPersistence()
                        // 15-min cap on Capella bring-up — covers ensure*, init, the cloud
                        // alloc round-trip (slower than docker; AWS may take minutes), bucket
                        // creation, CA fetch, and the N1QL curl. Same independent-budget
                        // rationale as the docker stage above.
                        timeout(unit: 'MINUTES', time: 15) {
                        deleteDir()
                        // Same pre-flight as the docker stage — see Task 8 / design §3.
                        // The Capella deployer doesn't pull Docker images locally, but the
                        // sdkqe agent may still be tight on disk after prior builds, and
                        // a consistent gate everywhere keeps the trade-off legible.
                        ensureDiskSpace(INTEGRATION_DISK_THRESHOLD_GB)
                        // Clone pipeline-scripts/ (see docker stage above for rationale).
                        checkoutPipelineRepo()

                        // Record runner-local docker version, hard-gate Python, and ensure
                        // cbdinocluster is present at the pinned version (downloads + verifies
                        // SHA-256 if not). Any flake here is attributable to a specific tooling
                        // version on the agent.
                        sh("docker --version")
                        ensurePython()
                        ensureCbdinocluster()

                        // TODO(TD-09): cbdinocluster's cloud deployer needs Capella credentials in
                        //              the per-agent config (`cbdinocluster init` reads
                        //              --capella-user / --capella-pass / --capella-oid, or env
                        //              vars CBDC_CAPELLA_USER, CBDC_CAPELLA_PASS, CBDC_CAPELLA_OID).
                        //              cbdyncluster used to pull these from baked-in agent state.
                        //              Until the SDK build agents are provisioned with those creds
                        //              (Jenkins credentials → withCredentials → env), this stage
                        //              will fail at `init --auto`. Wrap with withCredentials(...)
                        //              once the credential IDs exist. Related: TD-08.
                        sh("cbdinocluster -v init --auto")

                        writeFile file: "cluster.yaml", text: """\
cloud:
  cloud-provider: aws
nodes:
  - count: 3
    services: [kv, index, n1ql, eventing, fts, cbas]
expiry: 4h
"""
                        // Same rationale as the docker stage: the def must be readable
                        // straight from the build log.
                        sh("cat cluster.yaml")
                        CLUSTER.id_ = sh(script: "cbdinocluster -v alloc --deployer=cloud --def-file=cluster.yaml", returnStdout: true).trim()
                        // Guard before any downstream interpolation — and especially before the
                        // cleanup `cbdinocluster rm` runs against a billed cloud cluster.
                        if (!(CLUSTER.clusterId() ==~ /[A-Za-z0-9_-]+/)) {
                            error("cbdinocluster alloc returned an empty/invalid cluster id: '${CLUSTER.clusterId()}'")
                        }
                        sh("cbdinocluster buckets add ${CLUSTER.clusterId()} default --ram-quota-mb 256")
                        CLUSTER.firstIp = sh(script: "cbdinocluster ip ${CLUSTER.clusterId()}", returnStdout: true).trim()

                        // Fetch the Capella per-cluster CA so the curl below can validate TLS
                        // (no more `-k`) and so the test stage's TEST_CONNECTION_STRING carries
                        // `?trust_certificate=...`. Same extract_cert.py as the docker stage;
                        // a missing/empty `.cert` field exits non-zero with a clear message
                        // instead of producing an empty PEM that fails later as a confusing
                        // TLS handshake error.
                        //
                        // `cbdinocluster certificates get-ca` dispatches through whichever
                        // deployer owns the cluster (cloud, for Capella).
                        CLUSTER.certsDir = WORKSPACE
                        sh """
                            set -eu
                            if (set -o pipefail) 2>/dev/null; then set -o pipefail; fi
                            cbdinocluster certificates get-ca --json ${CLUSTER.clusterId()} \\
                                | python3 pipeline-scripts/cxx/scripts/extract_cert.py \\
                                    --cert-out ${CLUSTER.certsDir}/ca.pem
                        """

                        // TODO(TD-08): replace placeholder credentialsId `cxx-ci-capella-admin`
                        //              with the Capella admin credential configured in Jenkins.
                        //              Once provisioned, ROTATE P@ssword1 (treat it as burned —
                        //              it has shipped in pipeline source).
                        withCredentials([usernamePassword(credentialsId: 'cxx-ci-capella-admin', usernameVariable: 'CB_USER', passwordVariable: 'CB_PASS')]) {
                            withEnv([
                                "CB_QUERY_URL=https://${CLUSTER.firstIP()}:18093/query/service",
                                "CERTS_DIR=${CLUSTER.certsDir}"
                            ]) {
                                sh '''
                                    curl --fail-with-body --cacert "$CERTS_DIR/ca.pem" -sS -u "$CB_USER:$CB_PASS" "$CB_QUERY_URL" \
                                        -d 'statement=CREATE PRIMARY INDEX ON default USING GSI' \
                                        -d 'timeout=300s'
                                '''
                            }
                        }
                        // Append trust_certificate so cxx-client's TLS handshake during the test
                        // stage uses the same CA we just used for the index-create. Matches the
                        // docker stage's connstr-with-trust pattern (see the useTLS branch above).
                        CLUSTER.connstr = sh(script: "cbdinocluster connstr --tls ${CLUSTER.clusterId()}", returnStdout: true).trim() + "?trust_certificate=${CLUSTER.certsDir}/ca.pem"
                        }  // close cluster bring-up timeout
                    }
                    timeout(unit: 'MINUTES', time: 40) {
                        stage("test") {
                            unstash("${COMBINATION_PLATFORM}_build")
                            // TEST_PASSWORD is bound via withCredentials (Jenkins masks it in the
                            // console log). The username binding goes to an unused variable; if
                            // the test binary needs a username env var, rename it (e.g. to
                            // TEST_USERNAME) when wiring up the Jenkins credential.
                            withCredentials([usernamePassword(credentialsId: 'cxx-ci-capella-admin', usernameVariable: '_CAPELLA_USER', passwordVariable: 'TEST_PASSWORD')]) {
                                withEnv([
                                    "TEST_CONNECTION_STRING=${CLUSTER.connectionString()}",
                                    "CTEST_OUTPUT_ON_FAILURE=1",
                                    "TEST_LOG_LEVEL=trace",
                                    "TEST_DEPLOYMENT_TYPE=capella"
                                ]) {
                                dir("ws_${COMBINATION_PLATFORM}/couchbase-cxx-client") {
                                    try {
                                        sh("./bin/run-integration-tests")
                                    } finally {
                                        junit(testResults: "cmake-build-tests/results.xml", allowEmptyResults: true)
                                    }
                                }
                                }
                            }
                        }
                    }
                } finally {
                    stage("cleanup") {
                        // Same guard as the docker-cluster branch — except here the consequence
                        // of an unguarded `rm ""` is a leaked Capella cluster billing the org.
                        if (CLUSTER.clusterId()) {
                            sh("cbdinocluster rm ${CLUSTER.clusterId()}")
                        } else {
                            echo("skipping cbdinocluster rm: cluster id was never set (alloc likely failed)")
                        }
                    }
                }
            }
        }
        }  // if (CAPELLA_READY)
        // failFast aborts other CB-version stages as soon as one fails, freeing their
        // agents — same rationale as the build matrix above. The outer cap is the
        // budget for the whole integration parallel: a hung `cbdinocluster alloc` or
        // wedged docker daemon must not idle agents for the rest of the working day.
        // Per-stage timeouts inside each branch (cluster bring-up + 40-min test stage)
        // catch finer-grained hangs.
        //
        // Scaled per version branch rather than flat 120: the sdkqe pool has only
        // ~2 executors, so the branches largely SERIALIZE — worst healthy case is
        // ceil(N/2) waves of (25-min bring-up + 40-min test + overhead). A flat cap
        // sized for parallel execution kills healthy in-flight allocs, which is how
        // build #12 ended (queue wait ate the budget, abort landed mid-ready-wait).
        // 70 min/branch is the single-branch worst case; queueing halves effective
        // concurrency, so N * 70 / 2 + slack ≈ N * 40 + 60. failFast still ends
        // genuinely broken runs early.
        cbverStages.failFast = true
        timeout(unit: 'MINUTES', time: 40 * CB_VERSIONS.size() + 60) {
            parallel(cbverStages)
        }
    }
}
