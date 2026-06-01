def CMAKE_VERSION = "3.31.8"

// cbdinocluster version + per-asset SHA-256 map live in cxx/scripts/cbdinocluster.json
// (consumed by cxx/scripts/install_cbdinocluster.py). Bumping the cbdinocluster pin is
// a single-file edit there — version and every digest in lockstep. See ensureCbdinocluster()
// below for how the script is invoked from each agent.

// TODO(TD-10): expose PLATFORMS as a Jenkins job parameter (multi-select
//              list of user-facing platform names) with an internal map
//              from platform name to executor label. Today the platform
//              name doubles as the node label AND the stash-key prefix,
//              so narrowing the matrix for iteration breaks any stage
//              that dereferences ${COMBINATION_PLATFORM}_build. The
//              decoupling also lets contributors pick platforms from the
//              job form without editing this file.
def PLATFORMS = [
    // Temporarily reduced to alpine3.21-only for faster iteration while
    // debugging the matrix. Restore the full list before merging.
    "alpine3.21",
    // "rockylinux9",
    // "macos", // sonoma
    // "m1",  // sequoia
    // "msvc-2022",
    // "qe-rhel9-arm64",
    // "qe-ubuntu24-amd64",
    // "qe-ubuntu24-arm64",
]
def CB_VERSIONS = [
    "71release": [tag: "7.1-release"],
    "72stable": [tag: "7.2-stable"],
    "76stable": [tag: "7.6-stable"],
    "80stable": [tag: "8.0-stable"]
]

// no 7.0.4 release for community
if (USE_CE.toBoolean()) {
    CB_VERSIONS["70release"] = [tag: "7.0.2", label: "7.0-release"]
} else {
    CB_VERSIONS["70release"] = [tag: "7.0-release"]
}
def COMBINATION_PLATFORM = "rockylinux9"


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


stage("prepare and validate") {
    node("sdkqe-$COMBINATION_PLATFORM") {
        script {
            buildName([
                BUILD_NUMBER,
                PR_ID == "" ? null : "pr${PR_ID}",
                // STORAGE_BACKEND,
                USE_TLS ? "tls" : null,
                USE_CERT_AUTH ? "cert" : null,
            ].findAll { it != null }.join("-"))
        }
        cleanWs()

        stage("environment") {
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
            // Hard-gate: pipeline assumes python3 from this point on.
            // Better to fail here than mid-cert-handling 30 minutes later.
            ensurePython()
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


stage("build") {
    def builds = [:]
    for (p in PLATFORMS) {
        def platform = p
        builds[platform] = {
            node(platform) {
                // Per-platform timeout: a single hung node (network stall on a CPM fetch,
                // runaway compile, deadlocked test agent) shouldn't starve the rest of the
                // matrix of the outer 60-min budget. 45 min is comfortably above observed
                // worst-case (rocky9 cold build ~18 min, msvc-2022 ~25 min) without being
                // generous enough to hide a real hang.
                timeout(unit: 'MINUTES', time: 45) {
                stage("prep") {
                    // Per-node toolchain report: what THIS build node actually has, not just what
                    // prepare-and-validate saw. Best-effort — missing tools never fail the stage.
                    // Goal: when a build breaks on one platform, the build log already records the
                    // exact gcc/clang/cmake (and cl.exe on Windows, where reachable) it ran with,
                    // so triage doesn't require agent-image archaeology.
                    if (platform == "msvc-2022") {
                        powershell '''
                            Write-Host "===== OS / kernel ====="
                            [System.Environment]::OSVersion.ToString()
                            "machine $([System.Environment]::MachineName) ($([System.Environment]::ProcessorCount) cpus)"
                            Write-Host ""
                            Write-Host "===== Toolchain versions ====="
                            & cmake --version 2>&1 | Select-Object -First 1
                            & git --version 2>&1
                            "PowerShell $($PSVersionTable.PSVersion)"
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
                        if (platform == "msvc-2022") {
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
                    def envs = ["CB_NUMBER_OF_JOBS=4"]
                    if (platform == "macos") {
                        envs.push("OPENSSL_ROOT_DIR=/usr/local/opt/openssl")
                    } else if (platform == "m1") {
                        envs.push("OPENSSL_ROOT_DIR=/opt/homebrew/opt/openssl")
                    } else if (platform == "rockylinux9") {
                        envs.push("CB_CC=gcc")
                        envs.push("CB_CXX=g++")
                    }
                    def path = PATH
                    if (platform == "msvc-2022") {
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
                    } else if (platform == "macos" || platform == "m1") {
                        // macOS uses Homebrew's cmake. Fail loud if it's not installed — agents
                        // should be provisioned ahead of the build with `brew install cmake`, and
                        // a missing dep ought to abort here rather than be hidden by an
                        // opportunistic install. All Linux platforms — rockylinux9, alpine3.21,
                        // qe-rhel9-arm64, qe-ubuntu24-* — ship cmake ≥ 3.26 via their distro
                        // package managers (well above cxx-client's cmake_minimum_required(3.19)),
                        // so they fall through with no install at all.
                        sh '''
                            if ! brew list --versions cmake >/dev/null 2>&1; then
                                echo "ERROR: cmake is not installed via Homebrew on this agent." >&2
                                echo "       Provision the agent with: brew install cmake" >&2
                                exit 1
                            fi
                            echo "Using Homebrew cmake: $(brew list --versions cmake)"
                        '''
                        // Apple Silicon: /opt/homebrew/bin; Intel macOS: /usr/local/bin.
                        def brewBin = (platform == "m1") ? "/opt/homebrew/bin" : "/usr/local/bin"
                        path = "${brewBin}:" + path
                    }
                    echo("PATH=$path")
                    envs.push("PATH=$path")
                    withEnv(envs) {
                        try {
                            dir("ws_${platform}/couchbase-cxx-client") {
                                if (platform == "msvc-2022") {
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
                    if (platform == COMBINATION_PLATFORM) {
                        // The workspace was hydrated from a clean tarball (see "source tarball"
                        // sub-stage in prepare-and-validate), so default excludes are safe — no
                        // .git, no IDE metadata, no stray dotfiles to strip out.
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
    node("sdkqe-$COMBINATION_PLATFORM") {
        timeout(unit: 'MINUTES', time: 10) {
            stage("unit tests") {
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
            def version = v["tag"]
            def label = version
            if (v["label"] != null) {
                label = v["label"]
            }
            cbverStages["${COMBINATION_PLATFORM}-${label}"] = {
                node("sdkqe-$COMBINATION_PLATFORM") {
                    def CLUSTER = new DynamicCluster(version)
                    try {
                        stage(label) {
                            // 15-min cap on the cluster bring-up: docker version, ensure*,
                            // cbdinocluster init/alloc/buckets-add/cert-fetch/connstr, and the
                            // N1QL primary-index curl. A wedged `cbdinocluster alloc` or hung
                            // docker daemon must not eat into the 40-min test budget below —
                            // keeping them as independent timeouts makes the failure mode
                            // (bring-up hang vs test hang) immediately legible in the build log.
                            timeout(unit: 'MINUTES', time: 15) {
                            deleteDir()
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
expiry: 4h
"""

                            // init is idempotent; safe to re-run on any agent picking up this label.
                            sh("cbdinocluster -v init --auto")
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
                                sh """
                                    set -euo pipefail
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
                                    set -euo pipefail
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
                            // Guard the rm: alloc may have failed before CLUSTER.id_ was set, in
                            // which case `cbdinocluster rm ""` would either error and mask the
                            // real failure, or no-op silently. The guard mirrors the alloc
                            // validation above and keeps the original exception intact.
                            if (CLUSTER.clusterId()) {
                                sh("cbdinocluster rm ${CLUSTER.clusterId()}")
                            } else {
                                echo("skipping cbdinocluster rm: cluster id was never set")
                            }
                        }
                    }
                }
            }
        }
        cbverStages["${COMBINATION_PLATFORM}-capella"] = {
            node("sdkqe-$COMBINATION_PLATFORM") {
                def CLUSTER = new DynamicCluster("capella")
                try {
                    stage("capella") {
                        // 15-min cap on Capella bring-up — covers ensure*, init, the cloud
                        // alloc round-trip (slower than docker; AWS may take minutes), bucket
                        // creation, CA fetch, and the N1QL curl. Same independent-budget
                        // rationale as the docker stage above.
                        timeout(unit: 'MINUTES', time: 15) {
                        deleteDir()
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
                            set -euo pipefail
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
        // failFast aborts other CB-version stages as soon as one fails, freeing their
        // agents — same rationale as the build matrix above. Outer 120-min cap is the
        // budget for the whole integration parallel: a hung `cbdinocluster alloc` or
        // wedged docker daemon must not idle agents for the rest of the working day.
        // Per-stage timeouts inside each branch (cluster bring-up + 40-min test stage)
        // catch finer-grained hangs.
        cbverStages.failFast = true
        timeout(unit: 'MINUTES', time: 120) {
            parallel(cbverStages)
        }
    }
}
