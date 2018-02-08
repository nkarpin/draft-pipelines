/**
 * DEPLOY_JOB_NAME              Job name to deploy environmnets are going to be used for testes
 * DISTRIBUTION                 Distribution name for published repo. For example: dev-os-salt-formulas
 * COMPONENTS                   Components for repo. For example: salt
 * OPENSTACK_COMPONENTS_LIST    OpenStack related components list. For example: 'nova,cinder,glance,keystone,horizon,neutron,designate,heat,ironic,barbican'
 * prefixes                     Prefix packages to be published on the repo.  External storage can be passed with prefix for
 *                              example:
 *                                      def prefixes = ['oscc-dev', 's3:aptcdn:oscc-dev']
 * TMP_REPO_NODE_NAME           Node name where temp repo will be published
 * STACK_RECLASS_ADDRESS        URL for reclass model storage
 * OPENSTACK_RELEASES           OpenStack releases with comma delimeter which have to be testes. For example: pike,ocata
 * SOURCE_REPO_NAME             Name of the repo where packages are stored. For example: ubuntu-xenial-salt
 * APTLY_API_URL                URL to connect to aptly API. For example: http://172.16.48.254:8084
 * AUTO_PROMOTE                 True/False promote or not
 **/
common = new com.mirantis.mk.Common()
aptly = new com.mirantis.mk.Aptly()

/**
 * Had to override REST functions from pipeline-library here due to 'slashy-string issue'. The issue
 * appears during object type conversion to string by toString() method and an each quote is escaped by slash.
 * However even if sent data was defined as String explicitely then restCall function doesn't set request
 * property 'Content-Type' to 'application/json' and request are sent with webform header which is not acceptable
 * fot aptly api.
 **/

def restCall(master, uri, method = 'GET', data = null, headers = [:]) {
    def connection = new URL("${master.url}${uri}").openConnection()
    if (method != 'GET') {
        connection.setRequestMethod(method)
    }

    connection.setRequestProperty('User-Agent', 'jenkins-groovy')
    connection.setRequestProperty('Accept', 'application/json')
    if (master.authToken) {
        // XXX: removeme
        connection.setRequestProperty('X-Auth-Token', master.authToken)
    }

    for (header in headers) {
        connection.setRequestProperty(header.key, header.value)
    }

    if (data) {
        connection.setDoOutput(true)
        connection.setRequestProperty('Content-Type', 'application/json')

        def out = new OutputStreamWriter(connection.outputStream)
        out.write(data)
        out.close()
    }

    if ( connection.responseCode >= 200 && connection.responseCode < 300 ) {
        res = connection.inputStream.text
        try {
            return new groovy.json.JsonSlurperClassic().parseText(res)
        } catch (Exception e) {
            return res
        }
    } else {
        throw (connection.responseCode + ': ' + connection.inputStream.text)
    }
}

def restPost(master, uri, data = null) {
    return restCall(master, uri, 'POST', data, ['Accept': '*/*'])
}

def restGet(master, uri, data = null) {
    return restCall(master, uri, 'GET', data)
}

def restDel(master, uri, data = null) {
    return restCall(master, uri, 'DELETE', data)
}

/**
 * The function returen name of the snapshot belongs to
 * xenial/nightly repo considering the prefix with storage.
 *
 * @param server        URI the server to connect to aptly API
 * @param destribution  Destiribution of the repo which have to be found
 * @param prefix        Prefix of the repo including storage eg. prefix or s3:aptcdn:prefix
 * @param component     Component of the repo
 *
 * @return snapshot name
 **/
def getnightlySnapshot(server, distribution, prefix, component) {
    def list_published = restGet(server, '/api/publish')
    def storage

    for (items in list_published) {
        for (row in items) {
            if (prefix.tokenize(':')[1]) {
                storage = prefix.tokenize(':')[0] + ':' + prefix.tokenize(':')[1]
            } else {
                storage = ''
            }

            if (row.key == 'Distribution' && row.value == distribution && items['Prefix'] == prefix.tokenize(':').last() && items['Storage'] == storage) {
                for (source in items['Sources']){
                    if (source['Component'] == component) {
                        println ('Snapshot belongs to xenial/nightly: ' + source['Name'])
                        return source['Name']
                    }
                }
            }
        }
    }

    return false
}

/**
 * Returns list of the packages matched to pattern and
 * belonged to particular snapshot
 *
 * @param server        URI of the server insluding port and protocol
 * @param snapshot      Snapshot to check
 * @param packagesList  Pattern of the components to be compared
 **/

def snapshotPackages(server, snapshot, packagesList) {
    def pkgs = restGet(server, "/api/snapshots/${snapshot}/packages")
    def openstack_packages = []

    for (package_pattern in packagesList.tokenize(',')) {
        def pkg = pkgs.find { item -> item.contains(package_pattern) }
        openstack_packages.add(pkg)
    }

    return openstack_packages
}

/**
 * Creates snapshot of the repo or package refs
 * @param server        URI of the server insluding port and protocol
 * @param repo          Local repo name
 * @param packageRefs   List of the packages are going to be included into the snapshot
 **/
def snapshotCreate(server, repo, packageRefs = null) {
    def now = new Date()
    def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
    def snapshot = "os-salt-formulas-${ts}-oscc-dev"

    if (packageRefs) {
        String listString = packageRefs.join('\",\"')
//        println ("LISTSTRING: ${listString}")
        String data = "{\"Name\":\"${snapshot}\", \"Description\": \"OpenStack Core Components salt formulas CI\", \"PackageRefs\": [\"${listString}\"]}"
        echo "HTTP body is going to be sent: ${data}"
        def resp = restPost(server, '/api/snapshots', data)
        echo "Response: ${resp}"
    } else {
        String data = "{\"Name\": \"${snapshot}\", \"Description\": \"OpenStack Core Components salt formulas CI\"}"
        echo "HTTP body is going to be sent: ${data}"
        def resp = restPost(server, "/api/repos/${repo}/snapshots", data)
        echo "Response: ${resp}"
    }

    return snapshot
}

/**
 * Publishes the snapshot accodgin to distribution, components and prefix
 * @param server        URI of the server insluding port and protocol
 * @param snapshot      Snapshot is going to be published
 * @param distribution  Distribution for the published repo
 * @param components    Component for the published repo
 * @param prefix        Prefix for thepubslidhed repo including storage
 **/
def snapshotPublish(server, snapshot = null, distribution, components, prefix) {
//    def aptly = new com.mirantis.mk.Aptly()
    if (snapshot) {
        String data = "{\"SourceKind\": \"snapshot\", \"Sources\": [{\"Name\": \"${snapshot}\", \"Component\": \"${components}\" }], \"Architectures\": [\"amd64\"], \"Distribution\": \"${distribution}\"}"
        return restPost(server, "/api/publish/${prefix}", data)
    }

//    aptly.promotePublish(server['url'], 'xenial/nightly', "${prefix}/${distribution}", 'false', components, '', '', '-d --timeout 1200', '', '')

}

def snapshotUnpublish(server, prefix, distribution) {

    return restDel(server, "/api/publish/${prefix}/${distribution}")

}

node('python'){
    def server = [
        'url': APTLY_API_URL,
    ]
    def repo = SOURCE_REPO_NAME
    def components = COMPONENTS
    def prefixes = ['oscc-dev']
    def tmp_repo_node_name = TMP_REPO_NODE_NAME
//    def tmp_repo_node_name = 'apt.mcp.mirantis.net:8085'
//    def STACK_RECLASS_ADDRESS = 'https://gerrit.mcp.mirantis.net/salt-models/mcp-virtual-aio'
//    def OPENSTACK_RELEASES = 'ocata,pike'
//    def OPENSTACK_COMPONENTS_LIST = 'nova,cinder,glance,keystone,horizon,neutron,designate,heat,ironic,barbican'
    def notToPromote
//    def DEPLOY_JOB_NAME = 'oscore-MCP1.1-test-release-nightly'
    def testBuilds = [:]
    def deploy_release = [:]
    def distribution

    lock('aptly-api') {

        stage('Creating snapshot from nightly repo'){
            def nightlySnapshot = getnightlySnapshot(server, 'nightly', 'xenial', components)
            def snapshotpkglist = snapshotPackages(server, nightlySnapshot, OPENSTACK_COMPONENTS_LIST)

            snapshot = snapshotCreate(server, repo, snapshotpkglist)
            common.successMsg("Snapshot ${snapshot} has been created for packages: ${snapshotpkglist}")
        }

        stage('Publishing the snapshots'){
            def now = new Date()
            def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
            distribution = "${DISTRIBUTION}-${ts}"

            for (prefix in prefixes) {
                common.infoMsg("Publishing ${distribution} for prefix ${prefix} is started.")
                snapshotPublish(server, snapshot, distribution, components, prefix)
                common.successMsg("Snapshot ${snapshot} has been published for prefix ${prefix}")
            }
        }
    }

    stage('Deploying environment and testing'){
        for (openstack_release in OPENSTACK_RELEASES.tokenize(',')) {
            def release = openstack_release
            deploy_release["OpenStack ${release} deployment"] = {
                node('oscore-testing') {
                    testBuilds["${release}"] = build job: "${DEPLOY_JOB_NAME}-${release}", propagate: false, parameters: [
                        [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: "deb [arch=amd64] http://${tmp_repo_node_name}/oscc-dev ${distribution} ${components},1300,release n=${distribution}"],
                        [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: 'stable'],
                        [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: STACK_DELETE.toBoolean()],
                        [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: STACK_RECLASS_ADDRESS],
                        [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: "stable/${release}"],
                    ]
                }
            }
        }
    }

    stage('Running parallel OpenStack deployment') {
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

    }

   stage('Promotion to testing repo'){
       if (notToPromote) {
            error('Snapshot can not be promoted!!!')
       }
       if (common.validInputParam('AUTO_PROMOTE') && AUTO_PROMOTE.toBoolean() == true) {
            for (prefix in prefixes) {
                common.successMsg("${components} repo with prefix: ${prefix} distribution: ${distribution} snapshot: ${snapshot} will be promoted to testing")
                aptly.promotePublish(server['url'], "${prefix}/${distribution}", 'xenial/testing', 'false', components, OPENSTACK_COMPONENTS_LIST, '', '-d --timeout 1200', '', '')
            }
       }
   }

}
