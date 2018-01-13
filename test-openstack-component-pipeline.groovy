/**
 *
 * Wrapper pipeline for automated tests of Openstack components, deployed by MCP.
 * Pipeline stages:
 *  - Deployment of MCP environment with Openstack
 *  - Executing Smoke tests - set of tests to check basic functionality
 *  - Executing component tests - set of tests specific to component being tested,
 *    (or set of all tests).
 *
 * Flow parameters:
 *   EXTRA_REPO                        Deprecated. Repository with additional packages
 *   BOOTSTRAP_EXTRA_REPO_PARAMS       List of extra repos and related parameters injected on salt bootstrap stage:
 *                                     repo 1, repo priority 1, repo pin 1; repo 2, repo priority 2, repo pin 2
 *   REPO_URL                          URL to temporary repository with tested packages
 *   EXTRA_REPO_PIN                    Deprecated. Pin string for extra repo - eg "origin hostname.local"
 *   EXTRA_REPO_PRIORITY               Deprecated. Repo priority
 *   FAIL_ON_TESTS                     Whether to fail build on tests failures or not
 *   FORMULA_PKG_REVISION              Formulas release to deploy with (stable, testing or nightly)
 *   HEAT_STACK_ZONE                   VM availability zone
 *   OPENSTACK_VERSION                 Version of Openstack being tested
 *   OPENSTACK_API_URL                 OpenStack API address
 *   OPENSTACK_API_CREDENTIALS         Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT             OpenStack project to connect to
 *   OPENSTACK_API_PROJECT_DOMAIN      OpenStack project domain to connect to
 *   OPENSTACK_API_PROJECT_ID          OpenStack project ID to connect to
 *   OPENSTACK_API_USER_DOMAIN         OpenStack user domain
 *   OPENSTACK_API_CLIENT              Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION             Version of the OpenStack API (2/3)
 *   PROJECT                           Name of project being tested
 *   SALT_OVERRIDES                    Override reclass model parameters
 *   STACK_DELETE                      Whether to cleanup created stack
 *   STACK_TEST_JOB                    Job for launching tests
 *   STACK_TYPE                        Environment type (heat, physical, kvm)
 *   STACK_INSTALL                     Which components of the stack to install
 *   STACK_RECLASS_ADDRESS             Url to repository with stack salt models
 *   STACK_RECLASS_BRANCH              Branch of repository with stack salt models
 *   TEST_TEMPEST_CONF                 Tempest configuration file path inside container
 *                                     In case of runtest formula usage:
 *                                         TEST_TEMPEST_CONF should be align to runtest:tempest:cfg_dir and runtest:tempest:cfg_name pillars and container mounts
 *                                         Example: tempest config is generated into /root/rally_reports/tempest_generated.conf by runtest state.
 *                                                  Means /home/rally/rally_reports/tempest_generated.conf on docker tempest system.
 *                                     In case of predefined tempest config usage:
 *                                         TEST_TEMPEST_CONF should be a path to predefined tempest config inside container.
 *   TEST_TEMPEST_CONCURRENCY          Tempest tests concurrency
 *   TEST_TEMPEST_TARGET               Salt target for tempest tests
 *   TEST_TEMPEST_PATTERN              Tempest tests pattern - custom configuration for running
 *                                     tests, if it is manually set, TEST_TEMPEST_CONCURRENCY will
 *                                     be ignored, however '--concurrency' setting can be passed as
 *                                     part of TEST_TEMPEST_PATTERN string parameter, e. g.
 *                                     TEST_TEMPEST_PATTERN = 'volume --concurrency 5'
 *   TEST_MILESTONE                    MCP version
 *   TEST_MODEL                        Reclass model of environment
 *   TEST_PASS_THRESHOLD               Persent of passed tests to consider build successful
 *
 **/
common = new com.mirantis.mk.Common()
def artifactoryServer = Artifactory.server('mcp-ci')
def artifactoryUrl = artifactoryServer.getUrl()
def salt_overrides_list = SALT_OVERRIDES.tokenize('\n')
def build_disabled = 'disable-deploy-test'
def build_result = 'FAILURE'
def slave_node = 'python'

def get_test_pattern(project) {
    def pattern_map = ['cinder': 'volume',
                       // currently ironic and barbican tempest plugins aren't available
                       //'barbican': 'barbican_tempest_plugin',
                       'designate': 'designate_tempest_plugin',
                       'glance': 'image',
                       'heat': 'orchestration',
                       //'ironic': 'ironic_tempest_plugin',
                       'keystone': 'identity',
                       'nova': 'compute',
                       'neutron': 'network',]
    if (pattern_map.containsKey(project)) {
        return pattern_map[project]
    }
}

if (STACK_TYPE != 'heat' ) {
    slave_node = 'oscore-testing'
}

node(slave_node) {
    def project = PROJECT
    // EXTRA_REPO_* parameters are deprecated in favor of BOOTSTRAP_EXTRA_REPO_PARAMS
    def extra_repo
    if (common.validInputParam('EXTRA_REPO')) {
        extra_repo = EXTRA_REPO
    }
    def testrail = true
    def test_milestone = ''
    def test_tempest_concurrency = '2'
    def test_tempest_pattern = ''
    def stack_deploy_job = "deploy-${STACK_TYPE}-${TEST_MODEL}"
    def deployBuild
    def salt_master_url
    def stack_name
    def formula_pkg_revision = 'stable'
    def node_name = slave_node
    def use_pepper = false
    def bootstrap_extra_repo_params = ''
    // if stack reclass parameters are left empty, than default from heat template will be used
    def stack_reclass_address = ''
    def stack_reclass_branch = ''
    if (common.validInputParam('STACK_RECLASS_ADDRESS')) {
        stack_reclass_address = STACK_RECLASS_ADDRESS
    }
    if (common.validInputParam('STACK_RECLASS_BRANCH')) {
        stack_reclass_branch = STACK_RECLASS_BRANCH
    }
    if (common.validInputParam('TEST_TEMPEST_CONCURRENCY')) {
        test_tempest_concurrency = TEST_TEMPEST_CONCURRENCY
    }

    try {

        if (common.validInputParam('GERRIT_PROJECT')) {
            def pkgReviewNameSpace
            def repo_url
            // TODO: use decodeBase64 method and check if the string should be decoded
            def commit_message = sh(script: "echo ${GERRIT_CHANGE_COMMIT_MESSAGE} | base64 --decode", returnStdout: true).trim()
            if (commit_message.contains(build_disabled)) {
                build_result = 'NOT_BUILT'
                error("Found ${build_disabled} in commit message, skipping tests.")
            }
            // mcp/ocata and mcp/newton are hosted on review.fuel-infra.org
            if ((GERRIT_HOST == 'review.fuel-infra.org') && (GERRIT_BRANCH ==~ /mcp\/(newton|ocata)/)){
                // get project from review.fuel-infra.org project (e.g. nova from openstack/nova)
                project = GERRIT_PROJECT.tokenize('/')[1]
                pkgReviewNameSpace = "review/CR-${GERRIT_CHANGE_NUMBER}/mcp-repos/${OPENSTACK_VERSION}/xenial/"
                repo_url = "http://perestroika-repo-tst.infra.mirantis.net/${pkgReviewNameSpace} ${OPENSTACK_VERSION} main"
            } else {
                // get project from mcp-gerrit project (e.g. nova from packaging/specs/nova)
                project = GERRIT_PROJECT.tokenize('/')[2]
                pkgReviewNameSpace = "binary-dev-local/pkg-review/${GERRIT_CHANGE_NUMBER}"
                repo_url = REPO_URL ?: "${artifactoryUrl}/${pkgReviewNameSpace} /"
            }
            // currently artifactory CR repositories  aren't signed - related bug PROD-14585
            extra_repo = "deb [ arch=amd64 trusted=yes ] ${repo_url}"
            testrail = false
        } else {
            if (common.validInputParam('TEST_MILESTONE')) {
                test_milestone = TEST_MILESTONE
            }
            // overwrite testrail parameters for debug purposes
            if (common.validInputParam('TESTRAIL')) {
                testrail = TESTRAIL
            }
        }

        // setting pattern to run tests
        if (common.validInputParam('TEST_TEMPEST_PATTERN')) {
            test_tempest_pattern = TEST_TEMPEST_PATTERN
        } else if (get_test_pattern(project)) {
            test_tempest_pattern = "${get_test_pattern(project)} --concurrency ${test_tempest_concurrency}"
        }

        if (common.validInputParam('BOOTSTRAP_EXTRA_REPO_PARAMS')) {
            bootstrap_extra_repo_params = BOOTSTRAP_EXTRA_REPO_PARAMS
        }
        // Setting extra repo is deprecated, BOOTSTRAP_EXTRA_REPO_PARAMS should be used instead
        if (extra_repo) {
            // by default pin to fqdn of extra repo host
            def extra_repo_pin = "origin ${extra_repo.tokenize('/')[1]}"
            if (common.validInputParam('EXTRA_REPO_PIN')) {
                extra_repo_pin = EXTRA_REPO_PIN
            }
            def extra_repo_priority = '1200'
            if (common.validInputParam('EXTRA_REPO_PRIORITY')) {
                extra_repo_priority = EXTRA_REPO_PRIORITY
            }

            def extra_repo_params = ["linux_system_repo: ${extra_repo}",
                                     "linux_system_repo_priority: ${extra_repo_priority}",
                                     "linux_system_repo_pin: ${extra_repo_pin}",]
            for (item in extra_repo_params) {
               salt_overrides_list.add(item)
            }
        }

        if (common.validInputParam('FORMULA_PKG_REVISION')) {
            formula_pkg_revision = FORMULA_PKG_REVISION
        }

        if (salt_overrides_list) {
            common.infoMsg("Next salt model parameters will be overriden:\n${salt_overrides_list.join('\n')}")
        }

        if (STACK_TYPE == 'kvm') {
            // In order to access deployed vms from pipeline, we need to use pepper
            use_pepper = true
            // Deploy KVM environment
            stage('Trigger deploy KVM job') {
                deployBuild = build(job: 'oscore-deploy-kvm-VMs', propagate: false, parameters: [
                    [$class: 'BooleanParameterValue', name: 'DEPLOY_OPENSTACK', value: false],
                    [$class: 'BooleanParameterValue', name: 'DESTROY_ENV', value: false],
                    [$class: 'BooleanParameterValue', name: 'CREATE_ENV', value: true],
                    [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: stack_reclass_address],
                    [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: stack_reclass_branch],
                    [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides_list.join('\n')],
                ])
            }

            // get salt master url
            salt_master_url = "http://${deployBuild.description.tokenize(' ')[1]}:6969"
            node_name = "${deployBuild.description.tokenize(' ')[2]}"

            // Deploy MCP environment with upstream pipeline
            stage('Trigger deploy MCP job') {
                build(job: "deploy-heat-${TEST_MODEL}", propagate: false, parameters: [
                    [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: node_name],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                    [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                    [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                    [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                    [$class: 'StringParameterValue', name: 'STACK_TYPE', value: 'physical'],
                    [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: salt_master_url],
                    [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                    [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides_list.join('\n')],
                ])
            }
        } else {
            // Deploy MCP environment
            stage('Trigger deploy job') {
                deployBuild = build(job: stack_deploy_job, propagate: false, parameters: [
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                    [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                    [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                    [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: stack_reclass_address],
                    [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: stack_reclass_branch],
                    [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                    [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                    [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: formula_pkg_revision],
                    [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                    [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides_list.join('\n')],
                    [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: bootstrap_extra_repo_params],
                ])
            }
            // get salt master url
            salt_master_url = "http://${deployBuild.description.tokenize(' ')[1]}:6969"
            common.infoMsg("Salt API is accessible via ${salt_master_url}")
        }

        // Try to set stack name for stack cleanup job
        if (deployBuild.description) {
            stack_name = deployBuild.description.tokenize(' ')[0]
        }
        if (deployBuild.result != 'SUCCESS'){
            error("Deployment failed, please check ${deployBuild.absoluteUrl}")
        }

        // Perform smoke tests to fail early
        stage('Run Smoke tests') {
            build(job: STACK_TEST_JOB, parameters: [
                [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: node_name],
                [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: salt_master_url],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_CONF', value: TEST_TEMPEST_CONF],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: TEST_TEMPEST_TARGET],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_SET', value: 'smoke'],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_CONCURRENCY', value: test_tempest_concurrency],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: ''],
                [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
                [$class: 'BooleanParameterValue', name: 'USE_PEPPER', value: use_pepper],
                [$class: 'StringParameterValue', name: 'PROJECT', value: 'smoke'],
                [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '100'],
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
            ])
        }

        // Perform project specific tests
        if (test_tempest_pattern) {
            stage("Run ${test_tempest_pattern} tests") {
                build(job: STACK_TEST_JOB, parameters: [
                    [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: node_name],
                    [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: salt_master_url],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_CONF', value: TEST_TEMPEST_CONF],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: TEST_TEMPEST_TARGET],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_SET', value: ''],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_CONCURRENCY', value: ''],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: test_tempest_pattern],
                    [$class: 'StringParameterValue', name: 'TEST_MILESTONE', value: test_milestone],
                    [$class: 'StringParameterValue', name: 'TEST_MODEL', value: TEST_MODEL],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_VERSION', value: OPENSTACK_VERSION],
                    [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: testrail.toBoolean()],
                    [$class: 'BooleanParameterValue', name: 'USE_PEPPER', value: use_pepper],
                    [$class: 'StringParameterValue', name: 'PROJECT', value: project],
                    [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: TEST_PASS_THRESHOLD],
                    [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: FAIL_ON_TESTS.toBoolean()],
                ])
            }
        }
    } catch (Exception e) {
        currentBuild.result = build_result
        throw e
    } finally {
        //
        // Clean
        //
        if (common.validInputParam('STACK_DELETE') && STACK_DELETE.toBoolean() == true) {
            try {
                if (!stack_name || !node_name){
                    error('Stack cleanup parameters are undefined, cannot cleanup')
                }
                stage('Trigger cleanup job') {
                    common.errorMsg('Stack cleanup job triggered')
                    build(job: STACK_CLEANUP_JOB, parameters: [
                        [$class: 'StringParameterValue', name: 'STACK_NAME', value: stack_name],
                        [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: node_name],
                        [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: OPENSTACK_API_URL],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: OPENSTACK_API_CREDENTIALS],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: OPENSTACK_API_PROJECT_DOMAIN],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: OPENSTACK_API_PROJECT_ID],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: OPENSTACK_API_USER_DOMAIN],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: OPENSTACK_API_CLIENT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION],
                        [$class: 'BooleanParameterValue', name: 'DESTROY_ENV', value: true],
                    ])
                }
            } catch (Exception e) {
                common.errorMsg("Stack cleanup failed\n${e.message}")
            }
        }
    }
}
