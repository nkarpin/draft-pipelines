/**
*
* Test specified cookiecutter model changes.
*
* Expected parameters:
*
*    CREDENTIALS_ID                      ID of jenkins credentials to be used when connecting to gerrit.
*    COOKIECUTTER_TEMPLATE_CONTEXT_FILE  Context for coockiecutter template specified as filename.
*    COOKIECUTTER_EXTRA_CONTEXT          Extra context items, will be merged to COOKIECUTTER_TEMPLATE_CONTEXT_FILE
*
*    STACK_DELETE                        Delete Heat stack when finished (bool)
*
*
*    OPENSTACK_API_PROJECT               OpenStack project to connect to
*    HEAT_STACK_ZONE                     Heat stack zone where build stack
*
*    FLAVOR_PREFIX                       Flavors to use for heat environment.
*    STACK_INSTALL                       List of components to install
*
* Testing parameters:
*
*    RUN_SMOKE                           Bool value to specify if run smoke after deployment or not.
*/
import static groovy.json.JsonOutput.toJson

common = new com.mirantis.mk.Common()
aptly = new com.mirantis.mk.Aptly()
http = new com.mirantis.mk.Http()
gerrit = new com.mirantis.mk.Gerrit()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()

def stackName
def saltMasterUrl

def  deployJobName = 'create-mcp-env'

//def runSmoke = false
//if (common.validInputParam('RUN_SMOKE')) {
//  runSmoke = RUN_SMOKE.toBoolean()
//}

//def stackTestJob = 'oscore-tempest-runner'
//def testConcurrency = '2'
//def testConf = '/home/rally/rally_reports/tempest_generated.conf'
//def testTarget = 'gtw01'
def openstackEnvironment = 'devcloud'

def stackCleanupJob = 'delete-heat-stack-for-mcp-env'
def cookiecutterTemplateContextFile = COOKIECUTTER_TEMPLATE_CONTEXT_FILE
def cookiecutterTemplateCredentialsID = CREDENTIALS_ID
def cookiecutterContext = [:]

def installExtraFormula(saltMaster, formula) {
    def result
    result = salt.runSaltProcessStep(saltMaster, 'cfg01*', 'pkg.install', "salt-formula-${formula}")
    salt.checkResult(result)
    result = salt.runSaltProcessStep(saltMaster, 'cfg01*', 'file.symlink', ["/usr/share/salt-formulas/reclass/service/${formula}", "/srv/salt/reclass/classes/service/${formula}"])
    salt.checkResult(result)
}

def setContextDefault(contextObject, itemName, itemValue, contextName='default_context'){
    if (!contextObject[contextName].containsKey(itemName)){
      contextObject[contextName][itemName] = itemValue
    }
}

@SuppressWarnings ('ClosureAsLastMethodParameter')
def merge(Map onto, Map... overrides){
    if (!overrides){
        return onto
    }
    else if (overrides.length == 1) {
        overrides[0]?.each { k, v ->
            if (v in Map && onto[k] in Map){
                merge((Map) onto[k], (Map) v)
            } else {
                onto[k] = v
            }
        }
        return onto
    }
    return overrides.inject(onto, { acc, override -> merge(acc, override ?: [:]) })
}

timeout(time: 6, unit: 'HOURS') {
    node('oscore-testing') {

      try {
        stage('Prepare context'){
          def cookiecutterExtraContext = readYaml text: COOKIECUTTER_EXTRA_CONTEXT

          setContextDefault(cookiecutterExtraContext, 'cookiecutter_template_url', 'https://gerrit.mcp.mirantis.net/mk/cookiecutter-ttemplates')
          setContextDefault(cookiecutterExtraContext, 'cookiecutter_template_branch', 'master')

          def cookiecutterTemplateURL = cookiecutterExtraContext.default_context.cookiecutter_template_url
          def cookiecutterTemplateBranch = cookiecutterExtraContext.default_context.cookiecutter_template_branch

          def checkouted = false
          def cookiecutterTemplateContextFilePath = "cookiecutter-templates/contexts/oscore/${cookiecutterTemplateContextFile}.yml"
          if (cookiecutterTemplateBranch.startsWith('ref')){
              checkouted = gerrit.gerritPatchsetCheckout(cookiecutterTemplateURL, cookiecutterTemplateBranch, 'HEAD', cookiecutterTemplateCredentialsID)
          } else {
              checkouted = git.checkoutGitRepository('cookiecutter-templates', cookiecutterTemplateURL, cookiecutterTemplateBranch, cookiecutterTemplateCredentialsID)
          }
          if (checkouted && fileExists("${cookiecutterTemplateContextFilePath}")){
              cookiecutterBaseContext = readYaml(file: 'cookiecutter-templates/contexts/oscore/base.yml')
              def cookiecutterContextFragment = readYaml(file: "${cookiecutterTemplateContextFilePath}")
              merge(cookiecutterContext, cookiecutterBaseContext, cookiecutterContextFragment, cookiecutterExtraContext)
          } else {
              error("Cannot checkout gerrit or context file doesn't exists.")
          }
        }

        stage('Deploy cluster') {
          // Don't allow to set custom stack name
          wrap([$class: 'BuildUser']) {
            if (env.BUILD_USER_ID) {
              stackName = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
            } else {
              stackName = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
            }
          }

          deployBuild = build( job: "${deployJobName}", propagate: false, parameters: [
            [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
            [$class: 'StringParameterValue', name: 'COMPUTE_NODES_COUNT', value: '2'],
            [$class: 'StringParameterValue', name: 'FLAVOR_PREFIX', value: FLAVOR_PREFIX],
            [$class: 'StringParameterValue', name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment],
            [$class: 'StringParameterValue', name: 'OS_AZ', value: HEAT_STACK_ZONE],
            [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OPENSTACK_API_PROJECT],
            [$class: 'StringParameterValue', name: 'STACK_NAME', value: stackName],
            [$class: 'TextParameterValue', name: 'COOKIECUTTER_TEMPLATE_CONTEXT', value: toJson(cookiecutterContext)],
            [$class: 'BooleanParameterValue', name: 'DELETE_STACK', value: false],
            [$class: 'BooleanParameterValue', name: 'RUN_TESTS', value: false],
            ]
          )

          def venv = "${workspace}/venv"
          def saltMasterHost
          def openstackCloud
          def OS_AUTH_URL = 'https://cloud-cz.bud.mirantis.net:5000'

          // create openstack env
          openstack.setupOpenstackVirtualenv(venv)
          openstackCloud = openstack.createOpenstackEnv(venv,
                        OS_AUTH_URL, 'openstack-devcloud-credentials',
                        OPENSTACK_API_PROJECT, 'default',
                        '', 'default', '')
          saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, stackName, 'salt_master_ip', venv)
          // get salt master url
          saltMasterUrl = "http://${saltMasterHost}:6969"
          common.infoMsg("Salt API is accessible via ${saltMasterUrl}")
          currentBuild.description = "${stackName} ${saltMasterHost}"

          if (deployBuild.result != 'SUCCESS'){
            error("Deployment failed, please check ${deployBuild.absoluteUrl}")
          }
        }
/**
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
              [$class: 'StringParameterValue', name: 'TEST_PATTERN', value: 'smoke'],
              [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
              [$class: 'StringParameterValue', name: 'PROJECT', value: 'smoke'],
              [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '100'],
              [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
              [$class: 'BooleanParameterValue', name: 'USE_RALLY', value: use_rally],
            ])
          }
        }
**/

      } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
      } finally {
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
                [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OPENSTACK_API_PROJECT],
                [$class: 'StringParameterValue', name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment]
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
