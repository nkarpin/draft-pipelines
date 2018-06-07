/**
*
* Test specified cookiectuter changes.
*
* Expected parameters:
*    CREDENTIALS_ID               ID of jenkins credentials to be used when connecting to gerrit.
*
*    OPENSTACK_API_PROJECT               OpenStack project to connect to
*    HEAT_STACK_ZONE                     Heat stack zone where build stack
*    FLAVOR_PREFIX                       Flavors to use for heat environment.
*
*    TEST_SCHEME                  Yaml based scheme to test specific models.
*                                 ---
*                                 <cookiecutter_template_context_file_name>:
*                                   run_smoke: true
*                                   stack_install: core,openstack,ovs
*                                   ... other params
*
*    GERRIT_*                     Gerrit trigger plugin variables.
*
*/

def common = new com.mirantis.mk.Common()

def testScheme
def messages = ["${env.BUILD_URL}"]

def systestJob = 'oscore-test-cookiecutter-model'

def setGerritBuildString(buildObj, contextFile){
    return "* ${contextFile} ${buildObj.absoluteUrl} : ${buildObj.result} ${buildObj.durationString}"
}

node('oscore-testing') {

  testScheme = readYaml text: TEST_SCHEME

  def testBuilds = [:]
  stage('Deploy clusters') {
    def deploy_release = [:]
    for (contextFile in testScheme.keySet()) {
      def cf = contextFile
      deploy_release["Deploy ${cf}"] = {
        node('oscore-testing') {
          testBuilds["${cf}"] = build job: systestJob, propagate: false, parameters: [
            [$class: 'StringParameterValue', name: 'COOKIECUTTER_TEMPLATE_CONTEXT_FILE', value: cf],
            [$class: 'StringParameterValue', name: 'FLAVOR_PREFIX', value: FLAVOR_PREFIX],
            [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
            [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: testScheme[cf]['stack_install']],
            [$class: 'BooleanParameterValue', name: 'RUN_SMOKE', value: testScheme[cf]['run_smoke']],
            ]
          }
        }
      }
      parallel deploy_release
    }

    def success_models = []
    def failed_models = []
    stage('Managing deployment results') {
      testBuilds.each {
        if (it.value.result != 'SUCCESS') {
          failed_models.add("${it.key}: ${it.value.result}")
        } else {
          success_models.add("${it.key}: ${it.value.result}")
        }
        messages.add(0, setGerritBuildString(it.value, it.key))
        setGerritReview customUrl: messages.join('\n')
      }
    }
  if (success_models){
    common.successMsg(success_models.join('\n'))
  }
  if (failed_models) {
    common.errorMsg(failed_models.join('\n'))
    error('Some of deploy jobs failed')
  }
}
