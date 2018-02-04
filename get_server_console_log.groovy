common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
aws = new com.mirantis.mk.Aws()
orchestrate = new com.mirantis.mk.Orchestrate()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()

def getHeatStackResources(env, name, path = null, depth = 0) {
    def python = new com.mirantis.mk.Python()
    cmd = "heat resource-list --nested-depth ${depth} ${name}"
    outputTable = openstack.runOpenstackCommand(cmd, env, path)
    output = python.parseTextTable(outputTable, 'list', 'prettytable', path)
    return output
}

def getHeatStackServers(env, name, path = null) {
    resources = getHeatStackResources(env, name, path, 5)
    servers = [:]
    for (resource in resources) {
        if (resource.resource_type == 'OS::Nova::Server') {
            resourceName = resource.resource_name
            stackName = resource.stack_name
            server = openstack.getHeatStackResourceInfo(env, stackName, resourceName, path)
            print server.attributes.id
            id = server.attributes.id
            servers[id] = server.attributes.name
        }
    }
    echo("[Stack ${name}] Servers: ${servers}")
    return servers
}

node {
    venv = "${WORKSPACE}/openstack_venv_${JOB_NAME}-${BUILD_NUMBER}"
    // create openstack env
    openstack.setupOpenstackVirtualenv(venv, OPENSTACK_API_CLIENT)
    openstackCloud = openstack.createOpenstackEnv(venv,
        OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
        OPENSTACK_API_PROJECT, OPENSTACK_API_PROJECT_DOMAIN,
        OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
        OPENSTACK_API_VERSION)
    openstack.getKeystoneToken(openstackCloud, venv)
    
    stack_name = 'mkarpin-deploy-heat-virtual-mcp11-aio-1131'
    servers = getHeatStackServers(openstackCloud, stack_name, venv)
    
    print servers
    
    logs = [:]
    for (server in servers.keySet()){
        l = openstack.runOpenstackCommand("openstack console log show ${server} --lines 20000", openstackCloud, venv)
        logs["${server}"] = [servers[server], l]
    }
    
    print logs
}
