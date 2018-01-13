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
 *   TEST_TEMPEST_IMAGE           Docker image to run tempest
 *   TEST_TEMPEST_CONF            Tempest configuration file path inside container
 *                                In case of runtest formula usage:
 *                                    TEST_TEMPEST_CONF should be align to runtest:tempest:cfg_dir and runtest:tempest:cfg_name pillars and container mounts
 *                                    Example: tempest config is generated into /root/rally_reports/tempest_generated.conf by runtest state.
 *                                             Means /home/rally/rally_reports/tempest_generated.conf on docker tempest system.
 *                                In case of predefined tempest config usage:
 *                                    TEST_TEMPEST_CONF should be a path to predefined tempest config inside container.
 *   TEST_DOCKER_INSTALL          Install docker
 *   TEST_TEMPEST_TARGET          Salt target to run tempest on
 *   TEST_TEMPEST_PATTERN         Tempest tests pattern
 *   TEST_TEMPEST_CONCURRENCY     How much tempest threads to run
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
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
python = new com.mirantis.mk.Python()

// Define global variables
def saltMaster
def slave_node = 'python'

if (common.validInputParam('SLAVE_NODE')) {
    slave_node = SLAVE_NODE
}

node(slave_node) {

    def log_dir = "/home/rally/rally_reports/${PROJECT}/"
    def reports_dir = "/root/rally_reports/${PROJECT}"
    def date = sh(script: 'date +%Y-%m-%d', returnStdout: true).trim()
    def testrail = false
    def test_tempest_pattern = ''
    def test_milestone = ''
    def test_model = ''
    def venv = "${env.WORKSPACE}/venv"
    def test_tempest_concurrency = '0'
    def test_tempest_set = 'full'
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

        if (common.validInputParam('TEST_TEMPEST_CONCURRENCY')) {
            test_tempest_concurrency = TEST_TEMPEST_CONCURRENCY
        }

        stage ('Connect to salt master') {
            if (use_pepper) {
                python.setupPepperVirtualenv(venv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS, true)
                saltMaster = venv
            } else {
                saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }
        }

        stage ('Generate tempest configuration') {
            if (salt.testTarget(saltMaster, "I@runtest:tempest and ${TEST_TEMPEST_TARGET}")) {
                salt.enforceState(saltMaster, "I@runtest:tempest and ${TEST_TEMPEST_TARGET}", ['runtest'], true)
            } else {
                common.warningMsg('Cannot generate tempest config by runtest salt')
            }
        }

        if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
            test.install_docker(saltMaster, TEST_TEMPEST_TARGET)
        }

        if (common.validInputParam('LOCAL_TEMPEST_IMAGE')) {
            salt.cmdRun(saltMaster, TEST_TEMPEST_TARGET, "docker load --input ${LOCAL_TEMPEST_IMAGE}", true, null, false)
        }

        // TODO: implement stepler testing from this pipeline
        stage('Run OpenStack tests') {
            if (common.validInputParam('TEST_TEMPEST_SET')) {
                test_tempest_set = TEST_TEMPEST_SET
                common.infoMsg('TEST_TEMPEST_SET is set, TEST_TEMPEST_PATTERN parameter will be ignored')
            } else if (common.validInputParam('TEST_TEMPEST_PATTERN')) {
                test_tempest_pattern = TEST_TEMPEST_PATTERN
                common.infoMsg('TEST_TEMPEST_PATTERN is set, TEST_TEMPEST_CONCURRENCY and TEST_TEMPEST_SET parameters will be ignored')
            }

            test.runTempestTests(saltMaster, TEST_TEMPEST_IMAGE,
                                             TEST_TEMPEST_TARGET,
                                             test_tempest_pattern,
                                             log_dir,
                                             '/home/rally/keystonercv3',
                                             test_tempest_set,
                                             test_tempest_concurrency,
                                             TEST_TEMPEST_CONF)
            def tempest_stdout
            tempest_stdout = salt.cmdRun(saltMaster, TEST_TEMPEST_TARGET, "cat ${reports_dir}/report_${test_tempest_set}_*.log", true, null, false)['return'][0].values()[0].replaceAll('Salt command execution success', '')
            common.infoMsg('Short test report:')
            common.infoMsg(tempest_stdout)
        }

        stage('Archive rally artifacts') {
            test.archiveRallyArtifacts(saltMaster, TEST_TEMPEST_TARGET, reports_dir)
        }

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
