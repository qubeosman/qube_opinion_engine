import com.ca.io.qubeship.apis.QubeshipCommandResolver
import org.yaml.snakeyaml.Yaml

//String tnt_guid = "${tenant_id}"
String tnt_guid = "${qube_tenant_id}"
//String org_guid = "${org_id}"
String org_guid = "${qube_org_id}"
//String project_id = "${project_id}"
String project_id = "${qube_project_id}"

projectVariables = [:]
qubeYamlString = ''

node {
    // String commithash = "${commit_hash}"
    String commithash = "${commithash}"
    String refspec = "${refspec}"

    def project = null
    def toolchain = null
    def opinion = null
    def qubeConfig = null
    // def endpointsMap = [:]

    String toolchainRegistryUrl = ""
    String toolchainRegistryCredentialsPath = ""

    qubeship.inQubeshipTenancy(tnt_guid, org_guid, "https://api.qubeship.io") { qubeClient ->
        stage("init") {
            // load project
            project = qubeApi(httpMethod: "GET", resource: "projects", id: "${project_id}", qubeClient: qubeClient)
            if (commithash?.trim().length() == 0) {
                echo "replacing empty commit hash with refspec " + project.scm.refspec
                commithash = project.scm.refspec
            }

            // load owner info
            def owner = qubeApi(httpMethod: "GET", resource: "users", id: project.owner, exchangeToken: false, qubeClient: qubeClient)

            // checkout
            String owner_credentials_id = "qubeship:usercredentials:" + owner.credential
            checkout poll: false, scm: [$class: 'GitSCM',
                branches: [[name: "${commithash}"]],
                userRemoteConfigs: [[
                    credentialsId: owner_credentials_id,
                    url: project.scm.repoUrl,
                    refspec: project.scm.refspec
                ]]
            ]
            sh (script: "rm -Rf qube_utils")
            sh (script: "git clone https://github.com/Qubeship/qube_utils qube_utils",
                label:"Fetching qubeship scripts and templates")
            
            // get the contents of qube.yaml not from the API but the file in the source repo
            // String b64_encoded_qube_yaml = project.qubeYaml
            // byte[] b64_decoded = b64_encoded_qube_yaml.decodeBase64()
            // String qube_yaml = new String(b64_decoded)
            // qubeConfig = getYaml(qube_yaml)
            def qubeYamlFile = env.WORKSPACE + '/qube.yaml'
            qubeYamlString = sh(returnStdout: true, script: "cat $qubeYamlFile")
            qubeConfig = getYaml(qubeYamlString)
            initValidateQubeConfig(qubeConfig)

            // load toolchain
            toolchain = qubeApi(httpMethod: "GET", resource: "toolchains", id: project.toolchainId, qubeClient: qubeClient)
            // find the URL and credentials of the registry where the toolchain image is
            def toolchainRegistry = qubeApi(httpMethod: "GET", resource: "endpoints", id: toolchain.endpointId, qubeClient: qubeClient)
            if (toolchainRegistry) {
                toolchainRegistryUrl = toolchainRegistry.endPoint
                toolchainRegistryCredentialsPath = toolchainRegistry.credentialPath
            }
            else {
                toolchainRegistryUrl = 'https://gcr.io/'
                toolchainRegistryCredentialsPath = 'gcr:qubeship-partners'
            }

            // opinion
            opinion = qubeApi(httpMethod: "GET", resource: "opinions", id: project.opinionId, qubeClient: qubeClient)

            // abort the build if any required variable is missing
            String b64_encoded_opinion_yaml = opinion.yaml
            b64_decoded = b64_encoded_opinion_yaml.decodeBase64()
            String opinion_yaml_str = new String(b64_decoded)
            sh (returnStdout: true, script: "echo $b64_encoded_opinion_yaml | base64 -d > opinion.yaml")
            sh (returnStdout: true, script: "spruce merge --cherry-pick variables opinion.yaml qube.yaml qube_utils/merge_templates/variables.yaml > variables.yaml")
            variableConfig = getConfig(env.WORKSPACE + "/variables.yaml")
            Object[] vars = getArray(variableConfig.variables)
            for( int i = 0; i<vars?.length; i++){ 
                def var = vars[i];
                String varName = var.name
                boolean optional = var.optional
                String value = var.value
                println(varName + ', ' + optional)
                if (!optional && !value) {
                    error (String.format("Required variable(s) %s missing!", varName))
                }
                if (value?.trim()) {
                    projectVariables.put(varName, value)
                }
            }

            // resolve all qubeship args in projectVariables
            projectVariables = qubeship.resolveVariables('https://api.qubeship.io', tnt_guid, org_guid, project_id, projectVariables, qubeYamlString)
        }
        
        for (var in projectVariables) {
            println('*****************')
            println(var.key)
            println(var.value.first.value)
            println(var.value.second)
            println('*****************')
        }

        // TODO: find the way to get gcr credentials
        docker.withRegistry(toolchainRegistryUrl, toolchainRegistryCredentialsPath) {
            Object[] opinionList = getArray(opinion.opinionItems)
            process(opinionList, toolchain, qubeConfig)
        }
    }
}

def process(opinionList, toolchain, qubeConfig) {
    def toolchain_prefix = "gcr.io/qubeship-partners/"
    def toolchain_img = toolchain_prefix +  toolchain.imageName + ":" + toolchain.tagName
    
    for (int i=0; i<opinionList.length; i++){
        def item = opinionList[i];
        stage(item.name) {
            runStage(toolchain_img, item, toolchain, qubeConfig)
        }
    }   
}

def runStage(toolchain_img, stageObj, toolchain, qubeConfig) {
    // skip if the stage is skippable or throw error
    if ('skip' in qubeConfig[stageObj.name] && qubeConfig[stageObj.name]['skip']) {
        if (!stageObj.properties.skippable) {
            error ("Stage ${stageObj.name} cannot be skipped!")
        }
    } else {
        Object[] taskList = getArray(stageObj.tasks)
        if (stageObj.name == 'build') {
            runBuildTasks(toolchain_img, taskList, toolchain, qubeConfig)
        } else {
            for (int i = 0; i < taskList.length; i++) {
                def task = taskList[i];
                runTask(toolchain_img, task, toolchain, qubeConfig)
            }
        }
    }
}

def runTask(toolchain_img, task, toolchain, qubeConfig) {
    def taskDefInProject = null
    if (task.parent.name in qubeConfig && task.name in qubeConfig[task.parent.name]) {
        taskDefInProject = qubeConfig[task.parent.name][task.name]
    }
    
    // skip if the task is skippable or throw error
    if (taskDefInProject?.skip) {
        if (!task.properties.skippable) {
            error ("Task ${task.name} cannot be skipped!")
        }
    } else {
        // lookup in toolchain
        taskInToolchain = toolchain.manifestObject[task.parent.name+"." + task.name]

        def actions = []
        if (taskDefInProject?.actions) {
            // action arg1 arg2 ...
            for (action in taskDefInProject.actions) {
                // actions.add(action)
                actions << action
            }
        } else if (task.actions) {
            for (action in task.actions) {
                // actions.add(action)
                actions << action
            }
        } else {
            // actions.add(taskInToolchain)
            actions << taskInToolchain
        }

        def args = [:]
        int count = 0
        if (taskDefInProject?.args) {
            for (arg in taskDefInProject?.args) {
                count++
                args.put(count, arg)
            }   
        } else if (task.properties) {
            def taskDefaultArgs = task.properties.get("args")
            for (arg in taskDefaultArgs) {
                count++
                args.put(count, arg)
            }
        }

        def commands = qubeCommand(
            actions: actions,
            args: args,
            serverAddr: 'https://api.qubeship.io',
            globalVariablesMap: projectVariables,
            qubeYamlString: qubeYamlString)
        println(commands.size() + ' command(s) will be run:')
        for (command in commands) {
            println('credentialsMetadata.size(): ' + command.credentialsMetadata?.size());
            qubeship.withQubeCredentials(command.credentialsMetadata) {
                sh (script: command.fullQubeshipCommand)
            }
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

def getConfig(file) {
  def result = sh(returnStdout: true, script: "cat $file");
  def yaml = new Yaml();
  def config = yaml.load(result) ;
  return config
}

def isEmpty (value) {
  return (value == null|| value?.trim().length() == 0 )
}

def initValidateQubeConfig(qubeConfig) {
  if(isEmpty(qubeConfig.notification?.channel)) {
      qubeConfig.notification?.channel == "general"
  }
}

def runBuildTasks(toolchain_img, taskList, toolchain, qubeConfig) {
    String projectName = qubeConfig['name']
    String workdir = "/home/app"
    def builderImage = docker.image(
        prepareDockerFileForBuild(toolchain_img, projectName, workdir))

    builderImage.withRun("", "/bin/sh -c \"while true; do sleep 2; done\"") { container ->
        for (int i=0; i < taskList.length; i++) {
            List<String> scripts = new ArrayList<String>()

            def task = taskList[i];
            def taskDefInProject = qubeConfig[task.parent.name][task.name]
            def taskInToolchain = toolchain.manifestObject[task.parent.name+"."+task.name]

            List<String> actions = new ArrayList<String>();
            if (taskDefInProject?.actions) {
                // action arg1 arg2 ...
                for (action in taskDefInProject.actions) {
                    actions.add(action)
                }
            } else {
                actions.add(taskInToolchain)
            }

            String args = ""
            if (taskDefInProject?.args) {
                for (arg in taskDefInProject.args) {
                    args = args + " ${arg}"
                }
            }

            List<String> published_artifacts = new ArrayList<String>()
            if (taskDefInProject?.publish) {
                for (p in taskDefInProject.publish) {
                    published_artifacts.add(p)
                }
            }

            String command = null
            for (String action: actions) {
                String fullAction = action + " " + args
                command = (command == null) ? fullAction :  command + " && " + fullAction

                scripts.add(command)
            }

            for (String scriptStmt : scripts) {
                scriptStmt = "docker exec ${container.id} sh -c \"" + scriptStmt.trim() + "\""
                sh(script: scriptStmt)

                for (String artifact : published_artifacts) {
                    def copyStatement = "docker cp ${container.id}:${workdir}/${artifact} ."
                    sh(script: copyStatement, label:"Transfering artifacts from container")

                }
            }
        }
    }
}

def prepareDockerFileForBuild(image, project_name, workdir) {
    String dockerFile = "Dockerfile-build"
    String imageVersion = "${env.BUILD_NUMBER}"

    sh(script: "echo FROM ${image} > ${dockerFile} && \
    echo RUN mkdir -p ${workdir} >> ${dockerFile} && \
    echo WORKDIR ${workdir} >> ${dockerFile} && \
    echo ENV QUBE_BUILD_VERSION=${imageVersion} >> ${dockerFile} && \
    echo ADD . ${workdir} >> ${dockerFile}")

    sh(script: 'cat ' + dockerFile)

    String buiderImageTag = project_name + "-build"
    buiderImageTag = buiderImageTag.replaceAll("\s+|_+", "-").toLowerCase()
    sh(script: "docker build -t ${buiderImageTag} -f ${dockerFile} .")

    return buiderImageTag
}
