/**
 * AIO_JOB                      Job name to deploy all-in-one environmnets for testes
 * MULTINODE_JOB                Job name to deploy multinode environmnets for testes
 * COMPONENT                    Component name to be tested - e.g. openstack, salt etc.
 * SRC_REVISION                 Tested revision of component - e.g. nightly
 * TARGET_REVISION              Revision to promote SRC_REVISION to, e.g. testing
 * VERSION                      Version of tested component e.g queens for openstack
 * SYSTEM_DISTRIBUTION          Ubuntu release e.g. xenial
 * MULTINODE_JOB                Job name to deploy multi-node model
 * TEST_SCHEME                  Defines Json structure with parameters which should be passed to deployment e.g:
 *                              { "aio":{
 *                                 "virtual-mcp-aio": {
 *                                     "run_smoke": true,
 *                                     "branch": "stable/pike",
 *                                  }
 *                              }
 *                              For example 'aio:cluster-name1:branch1,branch2|multinode:cluster-name2:branch1,branch2'
 * AUTO_PROMOTE                 True/False promote or not
 * MIRROR_HOST                  Mirror host for package promotion
 **/
common = new com.mirantis.mk.Common()

def testScheme = readJSON text: TEST_SCHEME

timeout(time: 6, unit: 'HOURS') {
    node('oscore-testing'){

        def notToPromote
        def notToPromoteMulti
        def promoteSnapshotBuild
        def snapshot_id
        def snapshot
        if (common.validInputParam('SNAPSHOT_ID')) {
            snapshot_id = SNAPSHOT_ID
            snapshot = "${COMPONENT}-${VERSION}-${SYSTEM_DISTRIBUTION}-${SNAPSHOT_ID}"
            currentBuild.description = "${snapshot} -> ${TARGET_REVISION}"
        }
        def testBuilds = [:]
        def testBuildsMulti = [:]
        def deploy_release = [:]
        def formula_pkg_revision = 'testing'

        if (!snapshot_id) {
            def repo_target_url = "http://${MIRROR_HOST}/${SRC_REVISION}/${COMPONENT}-${VERSION}/${SYSTEM_DISTRIBUTION}.target.txt"

            stage('Getting mirror snapshot'){
                res = common.shCmdStatus("curl ${repo_target_url}")
                // ../../.snapshots/openstack-queens-xenial-2018-05-31-001100
                snapshot = res['stdout'].tokenize('/').last().trim()
                snapshot_id = snapshot.find(/\d{4}-\d{2}-\d{2}-\d{6}/)

                if (!snapshot_id || !res['status'] == 0) {
                    error("Cannot get snapshot id via url ${repo_target_url} or snapshot ${snapshot} has incorrect format")
                }
            }
            currentBuild.description = "${SRC_REVISION} -> ${TARGET_REVISION} (${snapshot})"
        }

        def bootstrap_extra_repo_params = "deb [ arch=amd64 trusted=yes ] http://${MIRROR_HOST}/.snapshots/${snapshot} ${SYSTEM_DISTRIBUTION} main,1200,release l=${VERSION}"

        stage('Deploying environment and testing'){

            def testSchemasAIO = testScheme['aio']
            common.infoMsg("Running AIO deployment on the following schemas: ${testSchemasAIO}")
            for (k in testSchemasAIO.keySet()){
                def cn = k
                def br = testSchemasAIO[k]['branch']
                deploy_release["Deploy ${cn} ${br}"] = {
                    node('oscore-testing') {
                        testBuilds["${cn}-${br}"] = build job: "${AIO_JOB}-${VERSION}", propagate: false, parameters: [
                            [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: bootstrap_extra_repo_params],
                            [$class: 'StringParameterValue', name: 'STACK_CLUSTER_NAME', value: cn],
                            [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: formula_pkg_revision],
                            [$class: 'BooleanParameterValue', name: 'RUN_SMOKE', value: testSchemasAIO[cn]['run_smoke']],
                            [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: true],
                            [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: br],
                        ]
                    }
                }
            }

            def testSchemasMultinode = testScheme['multinode']
            common.infoMsg("Running Multinode deployment on the following schemas: ${testSchemasMultinode}")
            for (k in testSchemasMultinode.keySet()){
                def cn = k
                def br = testSchemasMultinode[k]['branch']
                deploy_release["Deploy ${cn}-${br}"] = {
                    node('oscore-testing') {
                        testBuildsMulti["${cn}-${br}"] = build job: MULTINODE_JOB, propagate: false, parameters: [
                            [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: bootstrap_extra_repo_params],
                            [$class: 'StringParameterValue', name: 'STACK_CLUSTER_NAME', value: cn],
                            [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: formula_pkg_revision],
                            [$class: 'BooleanParameterValue', name: 'RUN_SMOKE', value: testSchemasMultinode[k]['run_smoke']],
                            [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: true],
                            [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: br],
                        ]
                    }
                }
            }
        }

        stage('Running parallel environment deployment') {
            parallel deploy_release
        }

        stage('Managing deployment results') {
            for (k in testBuilds.keySet()) {
                if (testBuilds[k].result != 'SUCCESS') {
                    notToPromote = true
                    common.errorMsg("${k} : " + testBuilds[k].result)
                } else {
                    common.successMsg("${k} : " + testBuilds[k].result)
                }
            }
            for (k in testBuildsMulti.keySet()) {
                if (testBuildsMulti[k].result != 'SUCCESS') {
                    notToPromoteMulti = true
                    common.errorMsg("${k} : " + testBuildsMulti[k].result)
                } else {
                    common.successMsg("${k} : " + testBuildsMulti[k].result)
                }
            }

        }

        stage("Promotion to ${TARGET_REVISION} snapshot"){
            if (notToPromote || notToPromoteMulti) {
                error('Snapshot can not be promoted!!!')
            }
            if (common.validInputParam('AUTO_PROMOTE') && AUTO_PROMOTE.toBoolean() == true) {
                common.successMsg("${COMPONENT} ${VERSION} ${snapshot_id} snapshot will be promoted to ${TARGET_REVISION} snapshot")
                promoteSnapshotBuild = build job: "mirror-snapshot-name-${COMPONENT}-${VERSION}-${SYSTEM_DISTRIBUTION}", propagate: false, parameters: [
                    [$class: 'StringParameterValue', name: 'SNAPSHOT_ID', value: snapshot_id],
                    [$class: 'StringParameterValue', name: 'SNAPSHOT_NAME', value: TARGET_REVISION],
                    [$class: 'StringParameterValue', name: 'MCP_VERSION', value: ''],
                ]
                if (promoteSnapshotBuild.result != 'SUCCESS'){
                    error("Promote snapshot job failed, please check ${promoteSnapshotBuild.absoluteUrl}")
                }
            }
        }
    }
}
