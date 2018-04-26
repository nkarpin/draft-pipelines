/**
*
* Test specified virtual model changes.
*
* Expected parameters:
*    CREDENTIALS_ID                    ID of jenkins credentials to be used when connecting to gerrit.

*    STACK_RECLASS_ADDRESS             Git URL to reclass model to use for deployment.
*    STACK_RECLASS_BRANCH              Git branch or ref of cluster model to test.
*    STACK_CLUSTER_NAME                The name of cluster to test.
*
*    FORMULA_PKG_REVISION              Formulas release to deploy with (stable, testing or nightly)
*    BOOTSTRAP_EXTRA_REPO_PARAMS       List of extra repos and related parameters injected on salt bootstrap stage:
*                                      repo 1, repo priority 1, repo pin 1; repo 2, repo priority 2, repo pin 2
*
*    DEPLOY_JOB_NAME                   The name of job to deploy cluster with. By default constructed with
*                                      "deploy-heat-$(STACK_CLUSTER_NAME.replace('-', '_'))"
*    STACK_TEST_JOB                    The name of job to perform test on the environment.
*    STACK_DELETE                      Whether to cleanup created stack
*
*    RUN_SMOKE                         Bool value to specify if run smoke after deployment or not.
*
*    OPENSTACK_VERSION                 Version of Openstack being tested
*    OPENSTACK_API_URL                 OpenStack API address
*    OPENSTACK_API_CREDENTIALS         Credentials to the OpenStack API
*    OPENSTACK_API_PROJECT             OpenStack project to connect to
*    OPENSTACK_API_PROJECT_DOMAIN      OpenStack project domain to connect to
*    OPENSTACK_API_PROJECT_ID          OpenStack project ID to connect to
*    OPENSTACK_API_USER_DOMAIN         OpenStack user domain
*    OPENSTACK_API_CLIENT              Versions of OpenStack python clients
*    OPENSTACK_API_VERSION             Version of the OpenStack API (2/3)
*
*
*/

common = new com.mirantis.mk.Common()
aptly = new com.mirantis.mk.Aptly()
http = new com.mirantis.mk.Http()
gerrit = new com.mirantis.mk.Gerrit()
openstack = new com.mirantis.mk.Openstack()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()

def stackName
def saltMasterUrl

def deployJobName
if (common.validInputParam('DEPLOY_JOB_NAME')) {
  deployJobName = DEPLOY_JOB_NAME
} else {
  deployJobName = "deploy-heat-${STACK_CLUSTER_NAME.replace('-', '_')}"
}

def runSmoke = true
if (common.validInputParam('RUN_SMOKE')) {
  runSmoke = RUN_SMOKE.toBoolean()
}

def stackTestJob
if (common.validInputParam('STACK_TEST_JOB')) {
  stackTestJob = STACK_TEST_JOB
} else {
  stackTestJob = "oscore-tempest-runner"
}

def testConcurrency
if (common.validInputParam('TEST_CONCURRENCY')) {
  testConcurrency = TEST_CONCURRENCY
} else {
  testConcurrency = "2"
}

def testConf
if (common.validInputParam('TEST_CONF')) {
  testConf = TEST_CONF
} else {
  testConf = "/home/rally/rally_reports/tempest_generated.conf"
}

def testTarget
if (common.validInputParam('TEST_TARGET')) {
  testTarget = TEST_TARGET
}

def stackCleanupJob
if (common.validInputParam('STACK_CLEANUP_JOB')) {
  stackCleanupJob = STACK_CLEANUP_JOB
} else {
  stackCleanupJob = 'deploy-stack-cleanup'
}

def extraRepo
if (common.validInputParam('BOOTSTRAP_EXTRA_REPO_PARAMS')) {
  extraRepo = BOOTSTRAP_EXTRA_REPO_PARAMS
} else {
  extraRepo = ''
}

def installExtraFormula(saltMaster, formula) {
    def result
    result = salt.runSaltProcessStep(saltMaster, 'cfg01*', 'pkg.install', "salt-formula-${formula}")
    salt.checkResult(result)
    result = salt.runSaltProcessStep(saltMaster, 'cfg01*', 'file.symlink', ["/usr/share/salt-formulas/reclass/service/${formula}","/srv/salt/reclass/classes/service/${formula}"])
    salt.checkResult(result)
}

timeout(time: 6, unit: 'HOURS') {
    node("oscore-testing") {

      try {
        stage('Deploy cluster') {
          deployBuild = build( job: "${deployJobName}", propagate: false, parameters: [
            [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: STACK_RECLASS_ADDRESS],
            [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: STACK_RECLASS_BRANCH],
            [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: FORMULA_PKG_REVISION],
            [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
            [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: extraRepo],
            [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
            ]
          )
          // get salt master url
          saltMasterUrl = "http://${deployBuild.description.tokenize(' ')[1]}:6969"
          common.infoMsg("Salt API is accessible via ${saltMasterUrl}")

          // Try to set stack name for stack cleanup job
          if (deployBuild.description) {
            stackName = deployBuild.description.tokenize(' ')[0]
          }
          if (deployBuild.result != 'SUCCESS'){
            error("Deployment failed, please check ${deployBuild.absoluteUrl}")
          }
        }

        // Perform smoke tests to fail early
        stage('Run tests'){
          if (runSmoke){
            common.infoMsg('Running smoke tests')
            build(job: stackTestJob, parameters: [
              [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: saltMasterUrl],
              [$class: 'StringParameterValue', name: 'TEST_CONF', value: testConf],
              [$class: 'StringParameterValue', name: 'TEST_TARGET', value: testTarget],
              [$class: 'StringParameterValue', name: 'TEST_SET', value: 'smoke'],
              [$class: 'StringParameterValue', name: 'TEST_CONCURRENCY', value: testConcurrency],
              [$class: 'StringParameterValue', name: 'TEST_PATTERN', value: ''],
              [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
              [$class: 'StringParameterValue', name: 'PROJECT', value: 'smoke'],
              [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '100'],
              [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
            ])
          }
        }

      } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
      } finally {
        stage ('Collecting artifacts') {
          try {
            def os_venv = "${WORKSPACE}/os-venv"
            def artifacts_dir = '_artifacts/'

            // create openstack env
            openstack.setupOpenstackVirtualenv(os_venv, OPENSTACK_API_CLIENT)
            def openstackCloud = openstack.createOpenstackEnv(os_venv,
              OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
              OPENSTACK_API_PROJECT, OPENSTACK_API_PROJECT_DOMAIN,
              OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
              OPENSTACK_API_VERSION)
            openstack.getKeystoneToken(openstackCloud, os_venv)

            // Get dictionary with ids and names of servers from deployed stack
            common.infoMsg("Getting servers from stack ${stackName}")
            def servers = openstack.getHeatStackServers(openstackCloud, stackName, os_venv)

            dir(artifacts_dir) {
              deleteDir()
              for (id in servers.keySet()){
                common.infoMsg("Getting console log from server ${servers[id]}")
                def l = openstack.runOpenstackCommand("openstack console log show ${id} --lines 20000", openstackCloud, os_venv)
                writeFile file: servers[id], text: l
              }
            }

            // TODO: implement upload to artifactory
            archiveArtifacts artifacts: "${artifacts_dir}/*"
          } catch (Exception e) {
            common.errorMsg("Collecting console logs failed\n${e.message}")
          }

          if (common.validInputParam('SALT_MASTER_CREDENTIALS') && common.validInputParam('ARTIFACTORY_CREDENTIALS')) {
            try {
              def creds = common.getCredentials(ARTIFACTORY_CREDENTIALS)
              def minions
              def saltMasterTarget = ['expression': 'cfg01*', 'type': 'compound']
              def result
              def extraFormulas = ["runtest", "artifactory"]
              def venv = "${env.WORKSPACE}/pepper-venv-${BUILD_NUMBER}"

              python.setupPepperVirtualenv(venv, saltMasterUrl, SALT_MASTER_CREDENTIALS)

              for (extraFormula in extraFormulas) {
                installExtraFormula(venv, extraFormula)
              }

              minions = salt.getMinions(venv, '*')
              for (minion in minions) {
                result = salt.runSaltCommand(venv, 'local', saltMasterTarget, 'reclass.node_update', null, null, ["name": "${minion}", "classes": ["service.runtest.tempest.artifactory"], "parameters": ["artifactory_user": "${creds.username}", "artifactory_password": "${creds.password.toString()}"]])
                salt.checkResult(result)
              }

              salt.fullRefresh(venv, "*")

              if (salt.testTarget(venv, 'I@runtest:artifact_collector')) {
                salt.enforceState(venv, 'I@runtest:artifact_collector', ['runtest.artifact_collector'], true)
              }
            } catch (Exception e) {
              common.errorMsg("Collecting environment artifacts failed\n${e.message}")
            }
          }
        }

        //
        // Clean stack
        //
        if (common.validInputParam('STACK_DELETE') && STACK_DELETE.toBoolean() == true) {
          try {
            if (!stackName){
              error('Stack cleanup parameters are undefined, cannot cleanup')
            }
            stage('Trigger cleanup job') {
              common.errorMsg('Stack cleanup job triggered')
              build(job: stackCleanupJob, parameters: [
                [$class: 'StringParameterValue', name: 'STACK_NAME', value: stackName],
                [$class: 'StringParameterValue', name: 'STACK_TYPE', value: 'heat'],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: OPENSTACK_API_URL],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: OPENSTACK_API_CREDENTIALS],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: OPENSTACK_API_PROJECT_DOMAIN],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: OPENSTACK_API_PROJECT_ID],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: OPENSTACK_API_USER_DOMAIN],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: OPENSTACK_API_CLIENT],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION],
                ]
              )
            }
          } catch (Exception e) {
            common.errorMsg("Stack cleanup failed\n${e.message}")
          }
        }
      }
    }
}
