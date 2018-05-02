/**
 *
 * Pipeline for tests execution on predeployed Openstack.
 * Pipeline stages:
 *  - Launch of tests on deployed environment. Currently
 *    supports only Tempest tests, support of Stepler
 *    will be added in future.
 *  - Archiving of tests results to Jenkins master
 *  - Processing results stage - triggers build of job
 *    responsible for results check and upload to testrail
 *
 * Expected parameters:
 *   LOCAL_TEMPEST_IMAGE          Path to docker image tar archive
 *   SALT_MASTER_URL              URL of Salt master
 *   SALT_MASTER_CREDENTIALS      Credentials to the Salt API
 *   TEST_IMAGE                   Docker image to run tempest
 *   TEST_CONF                    Tempest configuration file path inside container
 *                                In case of runtest formula usage:
 *                                    TEST_CONF should be align to runtest:tempest:cfg_dir and runtest:tempest:cfg_name pillars and container mounts
 *                                    Example: tempest config is generated into /root/rally_reports/tempest_generated.conf by runtest state.
 *                                             Means /home/rally/rally_reports/tempest_generated.conf on docker tempest system.
 *                                In case of predefined tempest config usage:
 *                                    TEST_CONF should be a path to predefined tempest config inside container
 *   TEST_DOCKER_INSTALL          Install docker
 *   TEST_TARGET                  Salt target to run tempest on e.g. gtw*
 *   TEST_PATTERN                 Tempest tests pattern
 *   TEST_CONCURRENCY             How much tempest threads to run
 *   TESTRAIL                     Whether upload results to testrail or not
 *   TEST_MILESTONE               Product version for tests
 *   TEST_MODEL                   Salt model used in environment
 *   PROJECT                      Name of project being tested
 *   OPENSTACK_VERSION            Version of Openstack being tested
 *   PROC_RESULTS_JOB             Name of job for test results processing
 *   FAIL_ON_TESTS                Whether to fail build on tests failures or not
 *   TEST_PASS_THRESHOLD          Persent of passed tests to consider build successful
 *   SLAVE_NODE                   Label or node name where the job will be run
 *   USE_PEPPER                   Whether to use pepper for connection to salt master
 *   USE_RALLY                    Whether to use rally to launch tests
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
python = new com.mirantis.mk.Python()

/**
 * Execute stepler tests
 *
 * @param dockerImageLink   Docker image link with stepler
 * @param target            Host to run tests
 * @param pattern           If not false, will run only tests matched the pattern
 * @param logDir            Directory to store stepler reports
 * @param sourceFile        Path to the keystonerc file in the container
 * @param set               Predefined set for tests
 * @param skipList          A skip.list's file name
 * @param localKeystone     Path to the keystonerc file in the local host
 * @param localLogDir       Path to local destination folder for logs
 */
def runSteplerTests(master, dockerImageLink, target, testPattern='', logDir='/home/stepler/tests_reports/',
                    set='', sourceFile='/home/stepler/keystonercv3', localLogDir='/root/rally_reports/',
                    skipList='skip_list_mcp_ocata.yaml', localKeystone='/root/keystonercv3') {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${localLogDir}"])
    def docker_run = "-e SOURCE_FILE=${sourceFile} " +
                     "-e LOG_DIR=${logDir} " +
                     "-e TESTS_PATTERN='${testPattern}' " +
                     "-e SKIP_LIST=${skipList} " +
                     "-e SET=${set} " +
                     "-v ${localKeystone}:${sourceFile} " +
                     "-v ${localLogDir}:${logDir} " +
                     '-v /etc/ssl/certs/:/etc/ssl/certs/ ' +
                     "${dockerImageLink} > docker-stepler.log"

    salt.cmdRun(master, "${target}", "docker run --rm --net=host ${docker_run}")
}

def installExtraFormula(saltMaster, formula) {
    def result
    result = salt.runSaltProcessStep(saltMaster, 'cfg01*', 'pkg.install', "salt-formula-${formula}")
    salt.checkResult(result)
    result = salt.runSaltProcessStep(saltMaster, 'cfg01*', 'file.symlink', ["/usr/share/salt-formulas/reclass/service/${formula}","/srv/salt/reclass/classes/service/${formula}"])
    salt.checkResult(result)
}

/**
 * Configure the node where runtest state is going to be executed
 *
 * @param nodename          nodename is going to be configured
 * @param test_target       Salt target to run tempest on e.g. gtw*
 * @param tempest_cfg_dir   directory to tempest configuration file
 * @param logdir            directory to put tempest log files
 **/

def configureRuntestNode(saltMaster, nodename, test_target, temtest_cfg_dir, logdir) {
    // Set up test_target parameter on node level
    def fullnodename = salt.getMinions(saltMaster, nodename).get(0)
    def saltMasterTarget = ['expression': 'I@salt:master', 'type': 'compound']
    def extraFormulas = ["runtest", "artifactory"]
    def result

    common.infoMsg("Setting up mandatory runtest parameters in ${fullnodename} on node level")

//    salt.runSaltProcessStep(saltMaster, fullnodename, 'pkg.install', ["salt-formula-runtest", "salt-formula-artifactory"])
    for (extraFormula in extraFormulas) {
        installExtraFormula(saltMaster, extraFormula)
    }
    result = salt.runSaltCommand(saltMaster, 'local', saltMasterTarget, 'reclass.node_update', null, null, ["name": "${fullnodename}", "classes": ["service.runtest.tempest"], "parameters": ["tempest_test_target": test_target, "runtest_tempest_cfg_dir": temtest_cfg_dir, "runtest_tempest_log_file": "${logdir}/tempest.log"]])
    salt.checkResult(result)

    common.infoMsg("Perform full refresh for all nodes")
    salt.fullRefresh(saltMaster, "*")

    common.infoMsg("Perform client states to create new resources")
    if (salt.testTarget(saltMaster, 'I@glance:client:enabled')) {
        salt.enforceState(saltMaster, 'I@glance:client:enabled', 'glance.client')
    }
    try {
        result = salt.cmdRun(saltMaster, 'I@keystone:server and *01*', '. /root/keystonercv3; openstack network list --external | grep ID')
        salt.checkResult(result)
    } catch (Exception e) {
        result = salt.runSaltCommand(saltMaster, 'local', saltMasterTarget, 'reclass.node_update', null, null, ["name": "${fullnodename}", "classes": ["service.runtest.tempest.public_net"]])
        salt.checkResult(result)
        salt.fullRefresh(saltMaster, fullnodename)
        if (salt.testTarget(saltMaster, 'I@neutron:client:enabled')) {
            salt.enforceState(saltMaster, 'I@neutron:client:enabled', 'neutron.client')
        }
    }
    if (salt.testTarget(saltMaster, 'I@nova:client:enabled')) {
        salt.enforceState(saltMaster, 'I@nova:client:enabled', 'nova.client')
    }
}

 /**
 * Execute tempest tests
 *
 * @param dockerImageLink       Docker image link with rally and tempest
 * @param target                Host to run tests
 * @param args                  Arguments that we pass in tempest
 * @param logDir                Directory to store tempest reports in container
 * @param localLogDir           Path to local destination folder for logs on host machine
 * @param tempestConfLocalPath  Path to tempest config on host machine
 */

def runTempestTestsNew(master, target, dockerImageLink, args = '', localLogDir='/root/test/', logDir='/root/tempest/',
                       tempestConfLocalPath='/root/test/tempest_generated.conf') {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${localLogDir}"])
    salt.cmdRun(master, "${target}", "docker run " +
                                    "-e ARGS=${args} " +
                                    "-v ${tempestConfLocalPath}:/etc/tempest/tempest.conf " +
                                    "-v ${localLogDir}:${logDir} " +
                                    "-v /etc/ssl/certs/:/etc/ssl/certs/ " +
                                    "-v /tmp/:/tmp/ " +
                                    "--rm ${dockerImageLink} " +
                                    "/bin/bash -c \"run-tempest\" ")
}

/** Archive Tempest results in Artifacts
 *
 * @param master              Salt connection.
 * @param target              Target node to install docker pkg
 * @param reports_dir         Source directory to archive
 */
def archiveTestArtifacts(master, target, reports_dir='/root/test', output_file='test.tar') {
    def salt = new com.mirantis.mk.Salt()

    def artifacts_dir = '_artifacts/'

    salt.cmdRun(master, target, "tar --exclude='env' -cf /root/${output_file} -C ${reports_dir} .")
    sh "mkdir -p ${artifacts_dir}"

    encoded = salt.cmdRun(master, target, "cat /root/${output_file}", true, null, false)['return'][0].values()[0].replaceAll('Salt command execution success','')
    writeFile file: "${artifacts_dir}${output_file}", text: encoded

    // collect artifacts
    archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
}

// Define global variables
def saltMaster
def slave_node = 'python'

if (common.validInputParam('SLAVE_NODE')) {
    slave_node = SLAVE_NODE
}

timeout(time: 6, unit: 'HOURS') {
    node(slave_node) {

        // remove when pipeline is switched to new container
        def use_rally = true
        if (common.validInputParam('USE_RALLY')){
            use_rally = USE_RALLY.toBoolean()
        }

        def test_type = 'tempest'
        if (common.validInputParam('TEST_TYPE')){
            test_type = TEST_TYPE
        }

        def log_dir = '/root/tempest/'
        // remove when pipeline is switched to new container
        if (use_rally){
            log_dir = '/home/rally/rally_reports/'
        }
        def reports_dir = '/root/test/'
        // remove when pipeline is switched to new container
        if (use_rally){
            reports_dir = '/root/rally_reports/'
        }

        def date = sh(script: 'date +%Y-%m-%d', returnStdout: true).trim()
        def test_log_dir = "/var/log/${test_type}"
        def testrail = false
        def args = ''
        def test_pattern = ''
        def test_milestone = ''
        def test_model = ''
        def venv = "${env.WORKSPACE}/venv"
        def test_concurrency = '0'
        def test_set = 'full'
        def use_pepper = true
        if (common.validInputParam('USE_PEPPER')){
            use_pepper = USE_PEPPER.toBoolean()
        }

        try {

            if (common.validInputParam('TESTRAIL') && TESTRAIL.toBoolean()) {
                testrail = true
                if (common.validInputParam('TEST_MILESTONE') && common.validInputParam('TEST_MODEL')) {
                    test_milestone = TEST_MILESTONE
                    test_model = TEST_MODEL
                } else {
                    error('WHEN UPLOADING RESULTS TO TESTRAIL TEST_MILESTONE AND TEST_MODEL MUST BE SET')
                }
            }

            if (common.validInputParam('TEST_CONCURRENCY')) {
                test_concurrency = TEST_CONCURRENCY
            }

            stage ('Connect to salt master') {
                if (use_pepper) {
                    python.setupPepperVirtualenv(venv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS, true)
                    saltMaster = venv
                } else {
                    saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
                }
            }

            configureRuntestNode(saltMaster, 'cfg01*', TEST_TARGET, reports_dir, log_dir)

            salt.runSaltProcessStep(saltMaster, TEST_TARGET, 'file.remove', ["${reports_dir}"])
            salt.runSaltProcessStep(saltMaster, TEST_TARGET, 'file.mkdir', ["${reports_dir}"])

            if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
                test.install_docker(saltMaster, TEST_TARGET)
            }

            if (common.validInputParam('LOCAL_TEMPEST_IMAGE')) {
                salt.cmdRun(saltMaster, TEST_TARGET, "docker load --input ${LOCAL_TEMPEST_IMAGE}", true, null, false)
            }

            // TODO: implement stepler testing from this pipeline
            stage('Run OpenStack tests') {

                if (test_type == 'stepler'){
                    runSteplerTests(saltMaster, TEST_IMAGE,
                        TEST_TARGET,
                        TEST_PATTERN,
                        '/home/stepler/tests_reports/',
                        '',
                        '/home/stepler/keystonercv3',
                        reports_dir)
                } else {

                    if (use_rally) {

                        if (common.validInputParam('TEST_SET')) {
                            test_set = TEST_SET
                            common.infoMsg('TEST_SET is set, TEST_PATTERN parameter will be ignored')
                        } else if (common.validInputParam('TEST_PATTERN')) {
                            test_pattern = TEST_PATTERN
                            common.infoMsg('TEST_PATTERN is set, TEST_CONCURRENCY and TEST_SET parameters will be ignored')
                        }

                    } else {

                        // There are some jobs which launch tempest runner with empty pattern and
                        // test_set=smoke
                        if (common.validInputParam('TEST_PATTERN')) {
                            test_pattern = TEST_PATTERN
                        } else {
                            test_pattern = 'smoke'
                        }

                        args = "\'-r ${test_pattern} -w ${test_concurrency}\'"
                    }

                    if (salt.testTarget(saltMaster, 'I@runtest:salttest')) {
                        salt.enforceState(saltMaster, 'I@runtest:salttest', ['runtest.salttest'], true)
                    }

                    if (salt.testTarget(saltMaster, 'I@runtest:tempest and cfg01*')) {
                        salt.enforceState(saltMaster, 'I@runtest:tempest and cfg01*', ['runtest'], true)
                    } else {
                        common.warningMsg('Cannot generate tempest config by runtest salt')
                    }

                    if (use_rally) {
                        test.runTempestTests(saltMaster, TEST_IMAGE,
                            TEST_TARGET,
                            test_pattern,
                            log_dir,
                            '/home/rally/keystonercv3',
                            test_set,
                            test_concurrency,
                            TEST_CONF)
                    } else {
                        // Remove this when job TEST_IMAGE is switched to new docker imge
                        if (!common.validInputParam('LOCAL_TEMPEST_IMAGE')) {
                            TEST_IMAGE = 'docker-prod-virtual.docker.mirantis.net/mirantis/cicd/ci-tempest'
                        }
                        runTempestTestsNew(saltMaster, TEST_TARGET, TEST_IMAGE, args)
                    }

                    def tempest_stdout
                    tempest_stdout = salt.cmdRun(saltMaster, TEST_TARGET, "cat ${reports_dir}/report_*.log", true, null, false)['return'][0].values()[0].replaceAll('Salt command execution success', '')
                    common.infoMsg('Short test report:')
                    common.infoMsg(tempest_stdout)
                }
            }

            stage('Archive Test artifacts') {
                if (use_rally){
                    test.archiveRallyArtifacts(saltMaster, TEST_TARGET, reports_dir)
                } else {
                    archiveTestArtifacts(saltMaster, TEST_TARGET, reports_dir)
                }
            }

            salt.runSaltProcessStep(saltMaster, TEST_TARGET, 'file.mkdir', ["${test_log_dir}"])
            salt.runSaltProcessStep(saltMaster, TEST_TARGET, 'file.move', ["${reports_dir}", "${test_log_dir}/${PROJECT}-${date}"])

            stage('Processing results') {
                build(job: PROC_RESULTS_JOB, parameters: [
                    [$class: 'StringParameterValue', name: 'TARGET_JOB', value: "${env.JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'TARGET_BUILD_NUMBER', value: "${env.BUILD_NUMBER}"],
                    [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: testrail.toBoolean()],
                    [$class: 'StringParameterValue', name: 'TEST_MILESTONE', value: test_milestone],
                    [$class: 'StringParameterValue', name: 'TEST_MODEL', value: test_model],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_VERSION', value: OPENSTACK_VERSION],
                    [$class: 'StringParameterValue', name: 'TEST_DATE', value: date],
                    [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: TEST_PASS_THRESHOLD],
                    [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: FAIL_ON_TESTS.toBoolean()]
                ])
            }

        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            throw e
        }
    }
}
