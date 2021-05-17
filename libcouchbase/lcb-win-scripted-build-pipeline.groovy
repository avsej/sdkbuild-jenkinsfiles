
// DO NOT EDIT: this file was generated from Jenkinsfile.erb
class Version {
    String gitVersion;

    int major;
    int minor;
    int patch;
    int commitCount;
    String prerelease;
    String commitSha1;

    Version(String gitVersion) {
        this.gitVersion = gitVersion.trim();
        parse()
    }

    @NonCPS
    void parse() {
        def res = (gitVersion =~ /^(\d+)\.(\d+)\.(\d+)(-(beta.\d+))?(-(\d+)-g([0-9a-f]+))?$/)
        res.find()
        this.major = res.group(1) as Integer
        this.minor = res.group(2) as Integer
        this.patch = res.group(3) as Integer
        if (res.group(5)) {
            this.prerelease = res.group(5)
        }
        if (res.group(7)) {
            this.commitCount = res.group(7) as Integer
        }
        if (res.group(8)) {
            this.commitSha1 = res.group(8)
        }
    }

    String version() {
        return "${major}.${minor}.${patch}"
    }

    String tar() {
        if (commitCount == null || commitCount == 0) {
            if (prerelease != null && prerelease != "") {
                return "${version()}_${prerelease}"
            } else {
                return version()
            }
        }
        return gitVersion.replace("-", "_")
    }

    String tarName() {
        return "libcouchbase-${tar()}"
    }

    String rpmVer() {
        return version()
    }

    String rpmRel() {
        def rel = "1"
        if (prerelease) {
            rel = "0.${prerelease}"
        } else if (commitCount) {
            rel = "${commitCount + 1}.git${commitSha1}"
        }
        return rel
    }

    String srpmGlob() {
        return "libcouchbase-${version()}-${rpmRel()}*.src.rpm"
    }

    String[] rpm() {
        return [version(), rpmRel()]
    }

    String deb() {
        def ver = version()
        if (prerelease) {
            ver += "~${prerelease}"
        } else if (commitCount) {
            ver += "~r${commitCount}git${commitSha1}"
        }
        return ver
    }
}

def VERSION = new Version('0.0.0')

class DynamicCluster {
    String id = null;
    String connstr = null;
    String server_version = null;

    boolean isAllocated() {
        return !(id == null || id == "")
    }

    String inspect() {
        return "Cluster(id: ${id}, connstr: ${connstr})"
    }
}

def CLUSTER = [:]

def doIntegrationStages(CLUSTER) {
    def returned_stages = [:]
    returned_stages['5.5.6'] = {
        node('sdkqe-centos7') {
            try {
                stage('start') {
                    sh("cbdyncluster ps -a")
                    script {
                       def cluster = new DynamicCluster()
                        CLUSTER['5.5.6'] = cluster
                        def ver = '5.5.6'.tokenize("_")[0]
                        cluster.id = sh(script: "cbdyncluster allocate --num-nodes=3 --server-version=${ver}", returnStdout: true).trim()
                        cluster.connstr = sh(script: "cbdyncluster ips ${cluster.id}", returnStdout: true).trim()
                    }
                    echo "Allocated ${CLUSTER['5.5.6'].inspect()}"
                    sh("cbdyncluster setup ${CLUSTER['5.5.6'].id} --node=kv,index,n1ql,fts --node=kv --node=kv --bucket=default")
                    script {
                        def isDp = '5.5.6'.tokenize("_").size() > 1
                        def ip = "${CLUSTER['5.5.6'].connstr}".tokenize(",")[0]
                        echo "isDp=${isDp}"
                        sh("""curl -vv -X POST -u Administrator:password http://${ip}:8091/sampleBuckets/install -d '["beer-sample"]'""")
                        sleep(30)
                        if (isDp) {
                           sh("curl -vv -X POST -u Administrator:password http://${ip}:8091/settings/developerPreview -d 'enabled=true'")
                        }
                        stage('test') {
                            try {
                                environment {
                                    LCB_TEST_CLUSTER_CONF="${CLUSTER['5.5.6'].connstr.replaceAll(',', ';')},default,Administrator,password"
                                    GTEST_SHUFFLE=1
                                }
                                unstash('centos7_build')
                                dir('ws_centos7_x64/build') {
                                    sh("pwd")
                                    sh("sed -i s:/home/couchbase/jenkins/workspace/lcb/lcb-scripted-build-pipeline/ws_centos7_x64/build:\$(realpath .):g tests/CTestTestfile.cmake")
                                    sleep(20)
                                    timeout(time: 60, unit: 'MINUTES') {
                                        sh("ctest -E BUILD ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                    }
                                }
                            } catch(all) {
                                sh('tar cf integration_failure-ws_centos7_5.5.6_x64.tar ws_centos7_x64')
                                archiveArtifacts(artifacts: "integration_failure-ws_centos7_5.5.6_x64.tar")
                            }
                        }
                    }
                }
            } finally {
                script {
                    if (CLUSTER['5.5.6'] && CLUSTER['5.5.6'].isAllocated()) {
                        sh("cbdyncluster rm ${CLUSTER['5.5.6'].id}")
                    }
                }
            }
        }
    }
    returned_stages['6.0.4'] = {
        node('sdkqe-centos7') {
            try {
                stage('start') {
                    sh("cbdyncluster ps -a")
                    script {
                       def cluster = new DynamicCluster()
                        CLUSTER['6.0.4'] = cluster
                        def ver = '6.0.4'.tokenize("_")[0]
                        cluster.id = sh(script: "cbdyncluster allocate --num-nodes=3 --server-version=${ver}", returnStdout: true).trim()
                        cluster.connstr = sh(script: "cbdyncluster ips ${cluster.id}", returnStdout: true).trim()
                    }
                    echo "Allocated ${CLUSTER['6.0.4'].inspect()}"
                    sh("cbdyncluster setup ${CLUSTER['6.0.4'].id} --node=kv,index,n1ql,fts --node=kv --node=kv --bucket=default")
                    script {
                        def isDp = '6.0.4'.tokenize("_").size() > 1
                        def ip = "${CLUSTER['6.0.4'].connstr}".tokenize(",")[0]
                        echo "isDp=${isDp}"
                        sh("""curl -vv -X POST -u Administrator:password http://${ip}:8091/sampleBuckets/install -d '["beer-sample"]'""")
                        sleep(30)
                        if (isDp) {
                           sh("curl -vv -X POST -u Administrator:password http://${ip}:8091/settings/developerPreview -d 'enabled=true'")
                        }
                        stage('test') {
                            try {
                                environment {
                                    LCB_TEST_CLUSTER_CONF="${CLUSTER['6.0.4'].connstr.replaceAll(',', ';')},default,Administrator,password"
                                    GTEST_SHUFFLE=1
                                }
                                unstash('centos7_build')
                                dir('ws_centos7_x64/build') {
                                    sh("pwd")
                                    sh("sed -i s:/home/couchbase/jenkins/workspace/lcb/lcb-scripted-build-pipeline/ws_centos7_x64/build:\$(realpath .):g tests/CTestTestfile.cmake")
                                    sleep(20)
                                    timeout(time: 60, unit: 'MINUTES') {
                                        sh("ctest -E BUILD ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                    }
                                }
                            } catch(all) {
                                sh('tar cf integration_failure-ws_centos7_6.0.4_x64.tar ws_centos7_x64')
                                archiveArtifacts(artifacts: "integration_failure-ws_centos7_6.0.4_x64.tar")
                            }
                        }
                    }
                }
            } finally {
                script {
                    if (CLUSTER['6.0.4'] && CLUSTER['6.0.4'].isAllocated()) {
                        sh("cbdyncluster rm ${CLUSTER['6.0.4'].id}")
                    }
                }
            }
        }
    }
    returned_stages['6.5.1'] = {
        node('sdkqe-centos7') {
            try {
                stage('start') {
                    sh("cbdyncluster ps -a")
                    script {
                       def cluster = new DynamicCluster()
                        CLUSTER['6.5.1'] = cluster
                        def ver = '6.5.1'.tokenize("_")[0]
                        cluster.id = sh(script: "cbdyncluster allocate --num-nodes=3 --server-version=${ver}", returnStdout: true).trim()
                        cluster.connstr = sh(script: "cbdyncluster ips ${cluster.id}", returnStdout: true).trim()
                    }
                    echo "Allocated ${CLUSTER['6.5.1'].inspect()}"
                    sh("cbdyncluster setup ${CLUSTER['6.5.1'].id} --node=kv,index,n1ql,fts --node=kv --node=kv --bucket=default")
                    script {
                        def isDp = '6.5.1'.tokenize("_").size() > 1
                        def ip = "${CLUSTER['6.5.1'].connstr}".tokenize(",")[0]
                        echo "isDp=${isDp}"
                        sh("""curl -vv -X POST -u Administrator:password http://${ip}:8091/sampleBuckets/install -d '["beer-sample"]'""")
                        sleep(30)
                        if (isDp) {
                           sh("curl -vv -X POST -u Administrator:password http://${ip}:8091/settings/developerPreview -d 'enabled=true'")
                        }
                        stage('test') {
                            try {
                                environment {
                                    LCB_TEST_CLUSTER_CONF="${CLUSTER['6.5.1'].connstr.replaceAll(',', ';')},default,Administrator,password"
                                    GTEST_SHUFFLE=1
                                }
                                unstash('centos7_build')
                                dir('ws_centos7_x64/build') {
                                    sh("pwd")
                                    sh("sed -i s:/home/couchbase/jenkins/workspace/lcb/lcb-scripted-build-pipeline/ws_centos7_x64/build:\$(realpath .):g tests/CTestTestfile.cmake")
                                    sleep(20)
                                    timeout(time: 60, unit: 'MINUTES') {
                                        sh("ctest -E BUILD ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                    }
                                }
                            } catch(all) {
                                sh('tar cf integration_failure-ws_centos7_6.5.1_x64.tar ws_centos7_x64')
                                archiveArtifacts(artifacts: "integration_failure-ws_centos7_6.5.1_x64.tar")
                            }
                        }
                    }
                }
            } finally {
                script {
                    if (CLUSTER['6.5.1'] && CLUSTER['6.5.1'].isAllocated()) {
                        sh("cbdyncluster rm ${CLUSTER['6.5.1'].id}")
                    }
                }
            }
        }
    }
    returned_stages['6.5.1_DP'] = {
        node('sdkqe-centos7') {
            try {
                stage('start') {
                    sh("cbdyncluster ps -a")
                    script {
                       def cluster = new DynamicCluster()
                        CLUSTER['6.5.1_DP'] = cluster
                        def ver = '6.5.1_DP'.tokenize("_")[0]
                        cluster.id = sh(script: "cbdyncluster allocate --num-nodes=3 --server-version=${ver}", returnStdout: true).trim()
                        cluster.connstr = sh(script: "cbdyncluster ips ${cluster.id}", returnStdout: true).trim()
                    }
                    echo "Allocated ${CLUSTER['6.5.1_DP'].inspect()}"
                    sh("cbdyncluster setup ${CLUSTER['6.5.1_DP'].id} --node=kv,index,n1ql,fts --node=kv --node=kv --bucket=default")
                    script {
                        def isDp = '6.5.1_DP'.tokenize("_").size() > 1
                        def ip = "${CLUSTER['6.5.1_DP'].connstr}".tokenize(",")[0]
                        echo "isDp=${isDp}"
                        sh("""curl -vv -X POST -u Administrator:password http://${ip}:8091/sampleBuckets/install -d '["beer-sample"]'""")
                        sleep(30)
                        if (isDp) {
                           sh("curl -vv -X POST -u Administrator:password http://${ip}:8091/settings/developerPreview -d 'enabled=true'")
                        }
                        stage('test') {
                            try {
                                environment {
                                    LCB_TEST_CLUSTER_CONF="${CLUSTER['6.5.1_DP'].connstr.replaceAll(',', ';')},default,Administrator,password"
                                    GTEST_SHUFFLE=1
                                }
                                unstash('centos7_build')
                                dir('ws_centos7_x64/build') {
                                    sh("pwd")
                                    sh("sed -i s:/home/couchbase/jenkins/workspace/lcb/lcb-scripted-build-pipeline/ws_centos7_x64/build:\$(realpath .):g tests/CTestTestfile.cmake")
                                    sleep(20)
                                    timeout(time: 60, unit: 'MINUTES') {
                                        sh("ctest -E BUILD ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                    }
                                }
                            } catch(all) {
                                sh('tar cf integration_failure-ws_centos7_6.5.1_DP_x64.tar ws_centos7_x64')
                                archiveArtifacts(artifacts: "integration_failure-ws_centos7_6.5.1_DP_x64.tar")
                            }
                        }
                    }
                }
            } finally {
                script {
                    if (CLUSTER['6.5.1_DP'] && CLUSTER['6.5.1_DP'].isAllocated()) {
                        sh("cbdyncluster rm ${CLUSTER['6.5.1_DP'].id}")
                    }
                }
            }
        }
    }
    returned_stages['6.6-stable'] = {
        node('sdkqe-centos7') {
            try {
                stage('start') {
                    sh("cbdyncluster ps -a")
                    script {
                       def cluster = new DynamicCluster()
                        CLUSTER['6.6-stable'] = cluster
                        def ver = '6.6-stable'.tokenize("_")[0]
                        cluster.id = sh(script: "cbdyncluster allocate --num-nodes=3 --server-version=${ver}", returnStdout: true).trim()
                        cluster.connstr = sh(script: "cbdyncluster ips ${cluster.id}", returnStdout: true).trim()
                    }
                    echo "Allocated ${CLUSTER['6.6-stable'].inspect()}"
                    sh("cbdyncluster setup ${CLUSTER['6.6-stable'].id} --node=kv,index,n1ql,fts --node=kv --node=kv --bucket=default")
                    script {
                        def isDp = '6.6-stable'.tokenize("_").size() > 1
                        def ip = "${CLUSTER['6.6-stable'].connstr}".tokenize(",")[0]
                        echo "isDp=${isDp}"
                        sh("""curl -vv -X POST -u Administrator:password http://${ip}:8091/sampleBuckets/install -d '["beer-sample"]'""")
                        sleep(30)
                        if (isDp) {
                           sh("curl -vv -X POST -u Administrator:password http://${ip}:8091/settings/developerPreview -d 'enabled=true'")
                        }
                        stage('test') {
                            try {
                                environment {
                                    LCB_TEST_CLUSTER_CONF="${CLUSTER['6.6-stable'].connstr.replaceAll(',', ';')},default,Administrator,password"
                                    GTEST_SHUFFLE=1
                                }
                                unstash('centos7_build')
                                dir('ws_centos7_x64/build') {
                                    sh("pwd")
                                    sh("sed -i s:/home/couchbase/jenkins/workspace/lcb/lcb-scripted-build-pipeline/ws_centos7_x64/build:\$(realpath .):g tests/CTestTestfile.cmake")
                                    sleep(20)
                                    timeout(time: 60, unit: 'MINUTES') {
                                        sh("ctest -E BUILD ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                    }
                                }
                            } catch(all) {
                                sh('tar cf integration_failure-ws_centos7_6.6-stable_x64.tar ws_centos7_x64')
                                archiveArtifacts(artifacts: "integration_failure-ws_centos7_6.6-stable_x64.tar")
                            }
                        }
                    }
                }
            } finally {
                script {
                    if (CLUSTER['6.6-stable'] && CLUSTER['6.6-stable'].isAllocated()) {
                        sh("cbdyncluster rm ${CLUSTER['6.6-stable'].id}")
                    }
                }
            }
        }
    }
    returned_stages['7.0-stable'] = {
        node('sdkqe-centos7') {
            try {
                stage('start') {
                    sh("cbdyncluster ps -a")
                    script {
                       def cluster = new DynamicCluster()
                        CLUSTER['7.0-stable'] = cluster
                        def ver = '7.0-stable'.tokenize("_")[0]
                        cluster.id = sh(script: "cbdyncluster allocate --num-nodes=3 --server-version=${ver}", returnStdout: true).trim()
                        cluster.connstr = sh(script: "cbdyncluster ips ${cluster.id}", returnStdout: true).trim()
                    }
                    echo "Allocated ${CLUSTER['7.0-stable'].inspect()}"
                    sh("cbdyncluster setup ${CLUSTER['7.0-stable'].id} --node=kv,index,n1ql,fts --node=kv --node=kv --bucket=default")
                    script {
                        def isDp = '7.0-stable'.tokenize("_").size() > 1
                        def ip = "${CLUSTER['7.0-stable'].connstr}".tokenize(",")[0]
                        echo "isDp=${isDp}"
                        sh("""curl -vv -X POST -u Administrator:password http://${ip}:8091/sampleBuckets/install -d '["beer-sample"]'""")
                        sleep(30)
                        if (isDp) {
                           sh("curl -vv -X POST -u Administrator:password http://${ip}:8091/settings/developerPreview -d 'enabled=true'")
                        }
                        stage('test') {
                            try {
                                environment {
                                    LCB_TEST_CLUSTER_CONF="${CLUSTER['7.0-stable'].connstr.replaceAll(',', ';')},default,Administrator,password"
                                    GTEST_SHUFFLE=1
                                }
                                unstash('centos7_build')
                                dir('ws_centos7_x64/build') {
                                    sh("pwd")
                                    sh("sed -i s:/home/couchbase/jenkins/workspace/lcb/lcb-scripted-build-pipeline/ws_centos7_x64/build:\$(realpath .):g tests/CTestTestfile.cmake")
                                    sleep(20)
                                    timeout(time: 60, unit: 'MINUTES') {
                                        sh("ctest -E BUILD ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                    }
                                }
                            } catch(all) {
                                sh('tar cf integration_failure-ws_centos7_7.0-stable_x64.tar ws_centos7_x64')
                                archiveArtifacts(artifacts: "integration_failure-ws_centos7_7.0-stable_x64.tar")
                            }
                        }
                    }
                }
            } finally {
                script {
                    if (CLUSTER['7.0-stable'] && CLUSTER['7.0-stable'].isAllocated()) {
                        sh("cbdyncluster rm ${CLUSTER['7.0-stable'].id}")
                    }
                }
            }
        }
    }
    return returned_stages
}

pipeline {
    agent none
    stages {
        stage('prepare and validate') {
            agent { label 'centos8 || centos7 || centos6' }
            steps {
                cleanWs()
                script {
                    if (IS_GERRIT_TRIGGER.toBoolean()) {
                        currentBuild.displayName = "cv-${BUILD_NUMBER}"
                    } else {
                        currentBuild.displayName = "full-${BUILD_NUMBER}"
                    }
                }

                dir('libcouchbase') {
                    checkout([$class: 'GitSCM', branches: [[name: '$SHA']], userRemoteConfigs: [[refspec: "$GERRIT_REFSPEC", url: '$REPO', poll: false]]])
                    script {
                        VERSION = new Version(sh(script: 'git describe --long --abbrev=10', returnStdout: true))
                        echo "Building ${VERSION.gitVersion}, gerrit: ${IS_GERRIT_TRIGGER}, release: ${IS_RELEASE}"
                    }
                }

                stash includes: 'libcouchbase/', name: 'libcouchbase', useDefaultExcludes: false

                dir('libcouchbase') {
                    dir('build') {
                        sh('cmake -DCMAKE_BUILD_TYPE=RelWithDebInfo -DLCB_NO_PLUGINS=1 -DLCB_NO_TESTS=1 -DLCB_NO_MOCK=1 ..')
                        sh('make dist')
                        archiveArtifacts(artifacts: "${VERSION.tarName()}.tar.gz", fingerprint: true)
                        stash includes: "${VERSION.tarName()}.tar.gz", name: 'tarball', useDefaultExcludes: false
                        withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                            s3Upload(
                                bucket: 'sdk-snapshots.couchbase.com',
                                file: "${VERSION.tarName()}.tar.gz",
                                path: 'libcouchbase/'
                            )
                        }
                    }
                }
            }
        }

        stage('build and test') {
            parallel {

                stage('w64v14s') {
                    agent { label 'msvc-2015' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win64_vc14_ssl') {
                                    deleteDir()
                                    bat('cbdep --platform windows_msvc2017 install  openssl 1.1.1g-sdk2')
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc14_ssl.zip', archive: false, dir: 'ws_win64_vc14_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc14.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win64_vc14_ssl/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 14 2015 Win64" -DOPENSSL_ROOT_DIR=..\\install\\openssl-1.1.1g-sdk2 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc14_ssl.zip', archive: false, dir: 'ws_win64_vc14_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc14_ssl.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win64_vc14_ssl/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win64_vc14_ssl/build') {
                                    bat('cmake --build . --target alltests')
                                    bat('copy ..\\install\\openssl-1.1.1g-sdk2\\bin\\*.dll bin\\Debug\\')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc14_ssl.zip', archive: false, dir: 'ws_win64_vc14_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc14_ssl.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win64_vc14_ssl/build') {
                                    bat('cmake --build . --target package')
                                    bat("move ${VERSION.tarName()}_vc14_amd64.zip ${VERSION.tarName()}_vc14_amd64_openssl.zip")
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc14_amd64_openssl.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc14_amd64_openssl.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w64v14') {
                    agent { label 'msvc-2015' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win64_vc14') {
                                    deleteDir()
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc14.zip', archive: false, dir: 'ws_win64_vc14')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc14.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win64_vc14/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 14 2015 Win64" -DLCB_NO_SSL=1 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc14.zip', archive: false, dir: 'ws_win64_vc14')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc14.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win64_vc14/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win64_vc14/build') {
                                    bat('cmake --build . --target alltests')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc14.zip', archive: false, dir: 'ws_win64_vc14')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc14.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win64_vc14/build') {
                                    bat('cmake --build . --target package')
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc14_amd64.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc14_amd64.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w32v14s') {
                    agent { label 'msvc-2015' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win32_vc14_ssl') {
                                    deleteDir()
                                    bat('cbdep --platform windows_msvc2017 install --x32 openssl 1.1.1g-sdk2')
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc14_ssl.zip', archive: false, dir: 'ws_win32_vc14_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc14.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win32_vc14_ssl/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 14 2015" -DOPENSSL_ROOT_DIR=..\\install\\openssl-1.1.1g-sdk2 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc14_ssl.zip', archive: false, dir: 'ws_win32_vc14_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc14_ssl.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win32_vc14_ssl/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win32_vc14_ssl/build') {
                                    bat('cmake --build . --target alltests')
                                    bat('copy ..\\install\\openssl-1.1.1g-sdk2\\bin\\*.dll bin\\Debug\\')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc14_ssl.zip', archive: false, dir: 'ws_win32_vc14_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc14_ssl.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win32_vc14_ssl/build') {
                                    bat('cmake --build . --target package')
                                    bat("move ${VERSION.tarName()}_vc14_x86.zip ${VERSION.tarName()}_vc14_x86_openssl.zip")
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc14_x86_openssl.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc14_x86_openssl.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w32v14') {
                    agent { label 'msvc-2015' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win32_vc14') {
                                    deleteDir()
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc14.zip', archive: false, dir: 'ws_win32_vc14')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc14.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win32_vc14/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 14 2015" -DLCB_NO_SSL=1 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc14.zip', archive: false, dir: 'ws_win32_vc14')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc14.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win32_vc14/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win32_vc14/build') {
                                    bat('cmake --build . --target alltests')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc14.zip', archive: false, dir: 'ws_win32_vc14')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc14.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win32_vc14/build') {
                                    bat('cmake --build . --target package')
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc14_x86.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc14_x86.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w64v15s') {
                    agent { label 'msvc-2017' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win64_vc15_ssl') {
                                    deleteDir()
                                    bat('cbdep --platform windows_msvc2017 install  openssl 1.1.1g-sdk2')
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc15_ssl.zip', archive: false, dir: 'ws_win64_vc15_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc15.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win64_vc15_ssl/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 15 2017 Win64" -DOPENSSL_ROOT_DIR=..\\install\\openssl-1.1.1g-sdk2 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc15_ssl.zip', archive: false, dir: 'ws_win64_vc15_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc15_ssl.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win64_vc15_ssl/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win64_vc15_ssl/build') {
                                    bat('cmake --build . --target alltests')
                                    bat('copy ..\\install\\openssl-1.1.1g-sdk2\\bin\\*.dll bin\\Debug\\')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc15_ssl.zip', archive: false, dir: 'ws_win64_vc15_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc15_ssl.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win64_vc15_ssl/build') {
                                    bat('cmake --build . --target package')
                                    bat("move ${VERSION.tarName()}_vc15_amd64.zip ${VERSION.tarName()}_vc15_amd64_openssl.zip")
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc15_amd64_openssl.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc15_amd64_openssl.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w64v15') {
                    agent { label 'msvc-2017' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win64_vc15') {
                                    deleteDir()
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc15.zip', archive: false, dir: 'ws_win64_vc15')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc15.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win64_vc15/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 15 2017 Win64" -DLCB_NO_SSL=1 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc15.zip', archive: false, dir: 'ws_win64_vc15')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc15.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win64_vc15/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win64_vc15/build') {
                                    bat('cmake --build . --target alltests')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc15.zip', archive: false, dir: 'ws_win64_vc15')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc15.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win64_vc15/build') {
                                    bat('cmake --build . --target package')
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc15_amd64.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc15_amd64.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w32v15s') {
                    agent { label 'msvc-2017' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win32_vc15_ssl') {
                                    deleteDir()
                                    bat('cbdep --platform windows_msvc2017 install --x32 openssl 1.1.1g-sdk2')
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc15_ssl.zip', archive: false, dir: 'ws_win32_vc15_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc15.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win32_vc15_ssl/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 15 2017" -DOPENSSL_ROOT_DIR=..\\install\\openssl-1.1.1g-sdk2 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc15_ssl.zip', archive: false, dir: 'ws_win32_vc15_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc15_ssl.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win32_vc15_ssl/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win32_vc15_ssl/build') {
                                    bat('cmake --build . --target alltests')
                                    bat('copy ..\\install\\openssl-1.1.1g-sdk2\\bin\\*.dll bin\\Debug\\')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc15_ssl.zip', archive: false, dir: 'ws_win32_vc15_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc15_ssl.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win32_vc15_ssl/build') {
                                    bat('cmake --build . --target package')
                                    bat("move ${VERSION.tarName()}_vc15_x86.zip ${VERSION.tarName()}_vc15_x86_openssl.zip")
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc15_x86_openssl.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc15_x86_openssl.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w32v15') {
                    agent { label 'msvc-2017' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win32_vc15') {
                                    deleteDir()
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc15.zip', archive: false, dir: 'ws_win32_vc15')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc15.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win32_vc15/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 15 2017" -DLCB_NO_SSL=1 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc15.zip', archive: false, dir: 'ws_win32_vc15')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc15.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win32_vc15/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win32_vc15/build') {
                                    bat('cmake --build . --target alltests')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win32_vc15.zip', archive: false, dir: 'ws_win32_vc15')
                                    archiveArtifacts(artifacts: 'failure-ws_win32_vc15.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win32_vc15/build') {
                                    bat('cmake --build . --target package')
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc15_x86.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc15_x86.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w64v16') {
                    agent { label 'msvc-2019' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win64_vc16') {
                                    deleteDir()
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc16.zip', archive: false, dir: 'ws_win64_vc16')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc16.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win64_vc16/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 16 2019" -DLCB_NO_SSL=1 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc16.zip', archive: false, dir: 'ws_win64_vc16')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc16.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win64_vc16/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win64_vc16/build') {
                                    bat('cmake --build . --target alltests')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc16.zip', archive: false, dir: 'ws_win64_vc16')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc16.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win64_vc16/build') {
                                    bat('cmake --build . --target package')
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc16_amd64.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc16_amd64.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                stage('w64v16s') {
                    agent { label 'msvc-2019' }
                    stages {
                        stage('prep') {
                            steps {
                                dir('ws_win64_vc16_ssl') {
                                    deleteDir()
                                    bat('cbdep --platform windows_msvc2017 install  openssl 1.1.1g-sdk2')
                                    unstash 'libcouchbase'
                                }
                            }
                        }
                        stage('build') {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc16_ssl.zip', archive: false, dir: 'ws_win64_vc16_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc16.zip', fingerprint: false)
                                }
                            }
                            steps {
                                dir('ws_win64_vc16_ssl/build') {
                                    bat('cmake --version --help')
                                    bat('cmake -G"Visual Studio 16 2019" -DOPENSSL_ROOT_DIR=..\\install\\openssl-1.1.1g-sdk2 ..\\libcouchbase')
                                    bat('cmake --build .')
                                }
                            }
                        }
                        stage('test') {
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            environment {
                                GTEST_SHUFFLE=1
                            }
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc16_ssl.zip', archive: false, dir: 'ws_win64_vc16_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc16_ssl.zip', fingerprint: false)
                                }
                                always {
                                    junit("ws_win64_vc16_ssl/build/*.xml")
                                }
                            }
                            steps {
                                dir('ws_win64_vc16_ssl/build') {
                                    bat('cmake --build . --target alltests')
                                    bat('copy ..\\install\\openssl-1.1.1g-sdk2\\bin\\*.dll bin\\Debug\\')
                                    bat("ctest --parallel=2 -C debug ${VERBOSE.toBoolean() ? '-VV' : ''}")
                                }
                            }
                        }
                        stage("pack") {
                            post {
                                failure {
                                    zip(zipFile: 'failure-ws_win64_vc16_ssl.zip', archive: false, dir: 'ws_win64_vc16_ssl')
                                    archiveArtifacts(artifacts: 'failure-ws_win64_vc16_ssl.zip', fingerprint: false)
                                }
                            }
                            when {
                                expression {
                                    return IS_GERRIT_TRIGGER.toBoolean() == false
                                }
                            }
                            steps {
                                dir('ws_win64_vc16_ssl/build') {
                                    bat('cmake --build . --target package')
                                    bat("move ${VERSION.tarName()}_vc16_amd64.zip ${VERSION.tarName()}_vc16_amd64_openssl.zip")
                                    archiveArtifacts(artifacts: "${VERSION.tarName()}_vc16_amd64_openssl.zip", fingerprint: true)
                                    withAWS(credentials: 'aws-sdk', region: 'us-east-1') {
                                        s3Upload(
                                            bucket: 'sdk-snapshots.couchbase.com',
                                            file: "${VERSION.tarName()}_vc16_amd64_openssl.zip",
                                            path: 'libcouchbase/'
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
