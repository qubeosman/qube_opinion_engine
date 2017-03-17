import org.yaml.snakeyaml.Yaml

node {
    String tnt_guid = "${tenant_id}"
    String org_guid = "${org_id}"
    String project_id = "${project_id}"
    String commithash = "${commit_hash}"

    def project = null
    def toolchain = null
    def opinion = null
    def qubeConfig = null

    stage("init") {
        // load project
        project = qubeApi(serverAddr: "http://mock-api.qubeship.io", httpMethod: "GET", resource: "projects",
          id: "${project_id}", tenantId: "${tnt_guid}", orgId: "${org_guid}")
        if (commithash?.trim().length() == 0) {
            echo "replacing empty commit hash with refspec " + project.scm.refspec
            commithash = project.scm.refspec
        }
        String b64_encoded_qube_yaml = project.qubeYaml
        byte[] b64_decoded = b64_encoded_qube_yaml.decodeBase64()
        String qube_yaml = new String(b64_decoded)
        qubeConfig = getYaml(qube_yaml)

        // load owner info
        def owner = qubeApi(serverAddr: "http://mock-api.qubeship.io", httpMethod: "GET", resource: "users",
          id: project.owner, tenantId: "${tnt_guid}", orgId: "", exchangeToken: false)

        // checkout
        String owner_credentials_id = "qubeship:production:" + owner.credential
        checkout poll: false, scm: [$class: 'GitSCM',
            branches: [[name: "${commithash}"]],
            userRemoteConfigs: [[
                credentialsId: owner_credentials_id,
                url: project.scm.repoUrl,
                refspec: project.scm.refspec
            ]]
        ]

        // load toolchain
        toolchain = qubeApi(serverAddr: "http://mock-api.qubeship.io", httpMethod: "GET", resource: "toolchains",
          id: project.toolchainId, tenantId: "${tnt_guid}", orgId: "${org_guid}")
        
        // opinion
        opinion = qubeApi(serverAddr: "http://mock-api.qubeship.io", httpMethod: "GET", resource: "opinions",
          id: project.opinionId, tenantId: "${tnt_guid}", orgId: "${org_guid}")
    }

    docker.withRegistry('https://gcr.io/', 'gcr:qubeship-partners') {
        process(opinion, toolchain, qubeConfig)
    }
}

def process(opinion, toolchain, qubeConfig) {
    def toolchain_prefix = "gcr.io/qubeship-partners/"
    def toolchain_img = toolchain_prefix +  toolchain.imageName + ":" + toolchain.tagName
    Object[] opinionList = getArray(opinion.opinionItems)
    for (int i=0; i<opinionList.length; i++){
        def item = opinionList[i];
        stage(item.name) {
            runStage(toolchain_img, item, toolchain, qubeConfig)
        }
    }   
}

@NonCPS
def getArray(def items) {
    Object[] array = items.toArray(new Object[items.size()])
    return array
}

@NonCPS
def getYaml(yamlStr) {
    def yaml = new Yaml()
    qube_yaml = yaml.load(yamlStr)
    return qube_yaml
}

def runStage(toolchain_img, stageObj, toolchain, qubeConfig) {
    echo 'stage: ' + stageObj.name
    Object[] taskList = getArray(stageObj.tasks)
    for (int i=0; i<taskList.length; i++){
        def task = taskList[i];
        runTask(toolchain_img, task, toolchain, qubeConfig)
    }  
}

@NonCPS
def runTask(toolchain_img, task, toolchain, qubeConfig) {
    //temporarily until endpoint resolution is resolved
    taskDefInProject = qubeConfig['project'][task.parent.name][task.name]
    // lookup in toolchain

    taskInToolchain = toolchain.manifestObject[task.parent.name+"." + task.name]
   
    ArrayList <String> actions = new ArrayList<String>();

    if (taskDefInProject) {
        if (taskDefInProject.actions) {
            // action arg1 arg2 ...
            for (action in taskDefInProject.actions) {
                //cmd = "${action}"
                actions.add(action)
            }
        }
    } else {
        actions.add(taskInToolchain)
    }

    args = ""
    if (taskDefInProject?.args) {
        for (arg in taskDefInProject?.args) {
            args = args + " ${arg}"
        }   
    }
    cid = UUID.randomUUID().toString()
    command = null
    for (String action: actions) {
        String fullAction = action + " " + args
        command = (command == null) ? fullAction :  command + " && " + fullAction
  
    }
    println("toolchain_img " + toolchain_img + " stage: " + 
    task.parent.name + "task :" + task.name + " command :" + command + 
    " id :" + cid)
    if(command == null || command?.trim().length() == 0  || command?.trim() == "null") {
        println("no definition available , skipping")
        return
    }  

    println("starting container launch " + cid + ":" +  actions.size())
    sh(script:"docker run --name $cid $toolchain_img $command")
    println("done " + cid)
    
}
