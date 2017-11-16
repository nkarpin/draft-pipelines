def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
def git = new com.mirantis.mk.Git()


node('python'){

    stage("Checkout"){
        checkouted = gerrit.gerritPatchsetCheckout([
            gerritName: 'mcp-jenkins',
            credentialsId: CREDENTIALS_ID,
            withLocalBranch: true,
            wipe: true,
        ])
    }

    stage("Running tests for ${GERRIT_PROJECT}"){
        build(job: PIPELINE_BUILD_JOB, parameters:[
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_CONF', value: 'aio_mcp.conf'],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: 'cfg01*'],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: 'smoke'],
                [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
                [$class: 'StringParameterValue', name: 'STACK_TYPE', value: 'heat'],
                [$class: 'StringParameterValue', name: 'PROJECT', value: 'nova'],
                [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '100'],
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
        ])
    }
}