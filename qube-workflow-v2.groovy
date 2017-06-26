import com.ca.io.qubeship.apis.QubeshipCommandResolver
import com.ca.io.qubeship.client.model.opinions.Opinion
import com.ca.io.qubeship.client.model.opinions.Stage
import com.ca.io.qubeship.client.model.toolchains.Toolchain

import com.ca.io.qubeship.utils.GramlClient
import org.yaml.snakeyaml.Yaml
import com.ca.io.qubeship.models.ValueWrapper
import com.ca.io.qubeship.models.StringValueWrapper
import com.ca.io.qubeship.models.SerializableTuple
import com.ca.io.qubeship.client.model.Endpoint
import static java.util.UUID.randomUUID

import groovy.json.JsonOutput

String tnt_guid = "${qube_tenant_id}"
String org_guid = "${qube_org_id}"
String project_id = "${qube_project_id}"

projectVariables = [:]
envVars = null
dynamicEnvVars = [:]
qubeYamlString = ''
artifactToPublish = []
artifactsImageId = ''

pipelineMetricsPayload = [
    "entity_id": project_id,
    "entity_type": "pipeline",
    "company": "",
    "tenant_id": tnt_guid,
    "org_id": org_guid,
    "is_system_user": "",
    "event_id": "",
    "event_type": "",
    "event_timestamp": "",
    "install_type": ""
]

node {
    String commithash = "${commithash}"

    def project = null
    def toolchain = null
    def opinion = null
    def qubeConfig = null
    // def endpointsMap = [:]

    String toolchainRegistryUrl = ""
    String toolchainRegistryCredentialsPath = ""
    String toolchainPrefix = null

    def opinionList = []

    String qubeshipUrl = "${env.QUBE_SERVER}"
    println("qubeshipUrl is " + qubeshipUrl)

    String analyticsEndpoint = "${env.ANALYTICS_ENDPOINT}"

    String run_id = randomUUID() as String

    boolean supportFortify = false
    boolean supportTwistlock = false
    def servicesList = [] as LinkedList

    try {
        qubeship.inQubeshipTenancy(tnt_guid, org_guid, qubeshipUrl) { qubeClient ->
            stage("init") {
                // load project
                for (int i = 0; i < 3; i++) { 
                    project = qubeApi(httpMethod: "GET", resource: "projects", id: "${project_id}", qubeClient: qubeClient)
                    if (project) {
                        break;
                    }
                    println("retrying.... ${project_id}")
                    sleep(3000)
                }
                if (commithash?.trim().length() == 0) {
                    echo "replacing empty commit hash with refspec " + project.scm.refspec
                    commithash = project.scm.refspec
                }
                // load owner info
                def owner = qubeApi(httpMethod: "GET", resource: "users", id: project.owner, exchangeToken: false, qubeClient: qubeClient)
                // signal: build start
                pipelineMetricsPayload['company'] = "${env.COMPANY}"
                pipelineMetricsPayload['install_type'] = "${env.INSTALL_TYPE}"
                pipelineMetricsPayload['is_system_user'] = owner.is_system_user
                pushPipelineEventMetrics(analyticsEndpoint, 'start', new Date())

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
                // sh (script: "rm -Rf qube_utils")
                sh (script: "if [ ! -d qube_utils ]; then git clone https://github.com/Qubeship/qube_utils qube_utils; else cd qube_utils; git pull; cd -; fi",
                    label:"Fetching qubeship scripts and templates")

                // get the contents of qube.yaml not from the API but the file in the source repo
                // String b64_encoded_qube_yaml = project.qubeYaml
                // byte[] b64_decoded = b64_encoded_qube_yaml.decodeBase64()
                // String qube_yaml = new String(b64_decoded)
                // qubeConfig = getYaml(qube_yaml)
                def qubeYamlFile = env.WORKSPACE + '/qube.yaml'

                qubeYamlString = sh(returnStdout: true, script: "if [ -e $qubeYamlFile ]; then cat $qubeYamlFile; fi")
                qubeConfig = getYaml(qubeYamlString)
                initValidateQubeConfig(qubeConfig)

                // load toolchain
                toolchain = qubeApi(httpMethod: "GET", resource: "toolchains", id: project.toolchainId, qubeClient: qubeClient)
                // find the URL and credentials of the registry where the toolchain image is
                def toolchainRegistry = qubeApi(httpMethod: "GET", resource: "endpoints", id: toolchain.endpointId, qubeClient: qubeClient)


                if (toolchainRegistry) {
                    toolchainRegistryUrl = toolchainRegistry.endPoint
                    if(toolchainRegistry.credentialPath) {
                        toolchainRegistryCredentialsPath = "qubeship:" + toolchainRegistry.category + ":" + toolchainRegistry.credentialPath
                    }
                    if (toolchainRegistry.additionalInfo) {
                        toolchainPrefix= toolchainRegistry.additionalInfo['account']
                    }
                }
                else {
                    toolchainRegistryUrl = 'https://index.docker.io/'
                    toolchainPrefix= "qubeship"
                }
                println("toolchain prefix:" + toolchainPrefix)
                println("toolchain credential:" + toolchainRegistryCredentialsPath)
                println("toolchain toolchainRegistryUrl:" + toolchainRegistryUrl)

                // TODO: opinion file name may be different
                String opinionYamlFilePath = env.WORKSPACE + '/opinion.yaml'
                String opinionYamlString = sh(returnStdout: true, script: "if [ -e $opinionYamlFilePath ]; then cat $opinionYamlFilePath; fi")
                if (opinionYamlString?.trim()) {
                    GramlClient gramlClient = new GramlClient(opinionYamlString)
                    opinionList = getArray(gramlClient.getStages(gramlClient.getStart("build")))
                }
                else {
                    // opinion
                    opinion = qubeApi(httpMethod: "GET", resource: "opinions", id: project.opinionId, qubeClient: qubeClient)
                    String b64_encoded_opinion_yaml = opinion.yaml
                    sh (returnStdout: true, script: "echo $b64_encoded_opinion_yaml | base64 -d > opinion.yaml")
                    opinionList = getArray(opinion.opinionItems)
                }

                sh (returnStdout: true, script: "spruce merge --cherry-pick variables opinion.yaml qube.yaml qube_utils/merge_templates/variables.yaml > variables.yaml")
                variableConfig = getConfig(env.WORKSPACE + "/variables.yaml")
                Object[] vars = getArray(variableConfig.variables)

                for (int i = 0; i < vars?.length; i++) {
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

                supportTwistlock = projectVariables['supportTwistlock']?.toBoolean() ?: false
                supportFortify = projectVariables['supportFortify']?.toBoolean() ?: false

                // resolve all qubeship args in projectVariables
                projectVariables = qubeship.resolveVariables(qubeshipUrl, tnt_guid, org_guid, project_id, projectVariables, qubeYamlString)
                envVars = qubeship.getEnvVars()
                envVarsString = " "
                if (envVars != null && projectVariables != null) {
                    for (qubeshipVariable in projectVariables) {
                        if (qubeshipVariable.value.getFirst().getType() in String) {
                            String envKey = qubeshipVariable.key
                            String envToBeExported = qubeshipVariable.value.getFirst().getValue()
                            if (envToBeExported) {
                                envVars.put(envKey, envToBeExported)
                                envVarsString += String.format("-e %s=%s ", envKey,envToBeExported)
                            }
                        }
                    }
                }
                if (supportFortify) {
                    servicesList << "fortify"
                }
                if (supportTwistlock) {
                    servicesList << "twistlock"
                }
            }
            try {
              preProcessCmdList = [] as LinkedList
              def action = this.&processOpinion 
              def index = 0
              docker.withRegistry(toolchainRegistryUrl, toolchainRegistryCredentialsPath) {
                  process(index, opinionList, toolchain, qubeConfig, qubeClient, envVarsString,toolchainPrefix,run_id, getArray(servicesList), preProcessCmdList, projectVariables, action)
              }
            } finally {
                stage('Publish Artifacts') {
                    def payloadImageId = """{
                        \"type\": \"image\",
                        \"contentType\": \"text/plain\",
                        \"title\": \"${artifactsImageId}\",
                        \"url\": \"${artifactsImageId}\",
                        \"isExternal\": false,
                        \"isResource\": false
                    }"""
                    def payloadLogURL = """{
                        \"type\": \"log\",
                        \"contentType\": \"text/plain\",
                        \"title\": \"Full Log\",
                        \"url\": \"${qubeshipUrl}/v1/pipelines/${project.id}/iterations/${env.BUILD_NUMBER}/logs\",
                        \"isExternal\": false,
                        \"isResource\": true
                    }"""
                    String pushTo = project.id + '/' + env.BUILD_NUMBER + '/artifacts'
                    if (artifactsImageId?.trim()){
                        qubeApiList(httpMethod: "POST", resource: "artifacts", qubeClient: qubeClient, subContextPath: pushTo, reqBody: payloadImageId)
                    }
                    qubeApiList(httpMethod: "POST", resource: "artifacts", qubeClient: qubeClient, subContextPath: pushTo, reqBody: payloadLogURL)

                    for (artifactItem in artifactToPublish) { 
                        def payloadItemURL = """{
                            \"type\": \"html\",
                            \"contentType\": \"text/html\",
                            \"title\": \"${artifactItem}\",
                            \"url\": \"${env.BUILD_URL}${artifactItem}\",
                            \"isExternal\": true,
                            \"isResource\": true
                        }"""

                        qubeApiList(httpMethod: "POST", resource: "artifacts", qubeClient: qubeClient, subContextPath: pushTo, reqBody: payloadItemURL)
                    }
                }
            }
        }
    } finally {
        // signal: build end
        containers_list = sh (returnStdout: true, script: "docker ps -aq --filter \"name=${run_id}-*\" | tr '\n' ' '")?.trim()
        if (containers_list) {
            sh (script: "docker rm -f ${containers_list}")
        }

        pushPipelineEventMetrics(analyticsEndpoint, 'end', new Date())
    }
}

def process(int index, opinionList, toolchain, qubeConfig, qubeClient, envVarsString, toolchainPrefix, run_id, servicesList, preProcessCmdList, projectVariables, action) {
    if(index < servicesList.length )   {
        def service = servicesList[index];
        def wrap = {  processor ->
            println(service)
            index++;
            if (service == "fortify") {
                //sh("docker exec ${container.id} sh -c \"cp /meta/fortify.license /opt/fortify\"")
                sh (script:"docker pull qubeship/fortify:4.21")
                println("processing service fortify")
                wrap([$class: 'ConfigFileBuildWrapper', 
                    managedFiles: [
                        [fileId: 'fortify.license', 
                        targetLocation: "/tmp/${run_id}/fortify.license"]]]) {
                    sh (script:"docker create --name ${run_id}-fortify qubeship/fortify:4.21")
                    sh (script:"docker cp /tmp/${run_id}/fortify.license ${run_id}-fortify:/opt/fortify/")
                    envVarsString+=" --volumes-from ${run_id}-fortify"
                    preProcessCmdList<<"docker exec #container.id# sh -c \"/opt/fortify/bin/fortify-install-maven-plugin.sh\""                    
                    println("calling next service")
                    processor.call()
                }
            } else if (service == "twistlock" && projectVariables["TWISTLOCK_ENDPOINT"]) {
                //special treatment for twistlock
                sh (script:"docker pull qubeship/twistlock:latest")
                sh (script: "docker create --name ${run_id}-twistlock qubeship/twistlock:latest")
                envVarsString += " --volumes-from ${run_id}-twistlock -v /var/run/docker.sock:/var/run/docker.sock"
                def twistlockEP = getQubeshipEntity(projectVariables["TWISTLOCK_ENDPOINT"])
                def twistlockEndpointURL = twistlockEP.endPoint
                def twistlockCredentialsPath=""
                if(twistlockEP.credentialPath) {
                    twistlockCredentialsPath = "qubeship:" + twistlockEP.category + ":" + twistlockEP.credentialPath
                }
                envVarsString += " -e TWISTLOCK_URL=${twistlockEndpointURL}"
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: twistlockCredentialsPath,
                    usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                    envVarsString += " -e TWISTLOCK_UNAME=${USERNAME} -e TWISTLOCK_PWD=${PASSWORD}"
                    processor.call()
                }
            } else {
                processor.call()
            }
        }
        wrap() { process(index, opinionList, toolchain, qubeConfig, qubeClient, envVarsString,toolchainPrefix,run_id, servicesList, preProcessCmdList,projectVariables, action) }
    }else{
        println("calling action")
        action(opinionList, toolchain, qubeConfig, qubeClient, envVarsString, toolchainPrefix, run_id, getArray(preProcessCmdList)) 
    }
}


def processOpinion(opinionList, toolchain, qubeConfig, qubeClient, envVarsString, toolchainPrefix, run_id, preProcessCmdList) {
    def toolchain_prefix = (toolchainPrefix?:"qubeship") + "/"
    def toolchain_img = toolchain_prefix +  toolchain.imageName + ":" + toolchain.tagName
    String projectName = qubeConfig['name']
    String workdir = "/home/app"
    String builderImageTag = prepareDockerFileForBuild(toolchain_img, run_id, projectName, workdir)
    def builderImage = docker.image(builderImageTag)
    def containerId=""
    try {
        builderImage.withRun(envVarsString, "tail -f /dev/null") { container ->
            containerId=container.id
            for (int i = 0; i < preProcessCmdList.length; i++) {  
                String preprocessCommand = preProcessCmdList[i]?.replaceAll("#container.id#", "${container.id}")
                println(preprocessCommand)
                sh (preprocessCommand)
            }
            runStage(opinionList[0], toolchain, qubeConfig, qubeClient, container, workdir,run_id)
        } 
    } finally {
        try {
            sh(script:"docker rmi ${builderImageTag}")
        } catch(Exception ex) {
            println("ERROR: " + ex.getMessage())
        }
    }

}

def runStage(stageObj, toolchain, qubeConfig, qubeClient, container, workdir,run_id) {
    stage(stageObj.name) {
        // skip if the stage is skippable or throw error
        if ('skip' in qubeConfig[stageObj.name] && qubeConfig[stageObj.name]['skip']) {
            if (!stageObj.properties.skippable) {
                error ("Stage ${stageObj.name} cannot be skipped!")
            }
        } else {
            Object[] taskList = getArray(stageObj.tasks)
            for (int i = 0; i < taskList.length; i++) {
                def task = taskList[i];
                status=runTask(task, toolchain, qubeConfig, qubeClient, container, workdir, run_id)
            }
        }
    }
    if(stageObj.getProperties().containsKey("next")) {
        def nextItems = (LinkedList<Stage>)stageObj.getProperties().get("next");
        Object[] stages=getArray(nextItems);
        for( int i = 0; i<stages?.length; i++){ 
           runStage(stages[i], toolchain, qubeConfig, qubeClient, container, workdir,run_id) 
        }
    }else{
        println("stage : " + stageObj.name + " : complete")
    }

}

def runTask(task, toolchain, qubeConfig, qubeClient, container=null, workdir=null, run_id=null) {
    def taskDefInProject = null
    if (task.parent.name in qubeConfig && task.name in qubeConfig[task.parent.name]) {
        println("found taskdef in project: " + task.parent.name + ":" + task.name)
        taskDefInProject = qubeConfig[task.parent.name][task.name]
    }

    // skip if the task is skippable or throw error
    if (taskDefInProject?.skip) {
        if (!task.properties.skippable) {
            error ("Task ${task.name} cannot be skipped!")
        }
    } else {
        boolean defaultExecuteOutsideToolchainPreference = (task.parent.name != 'build')
        println('defaultExecuteOutsideToolchainPreference: ' + defaultExecuteOutsideToolchainPreference)
        boolean executeOutsideToolchain = defaultExecuteOutsideToolchainPreference
        if (task.properties.get('execute_outside_toolchain')!=null) {
            executeOutsideToolchain = task.properties.get('execute_outside_toolchain')
        }
        boolean executeInToolchain = !executeOutsideToolchain
        println('running inside toolchain? ' + executeInToolchain)

        // lookup in toolchain
        taskInToolchain = toolchain.manifestObject[task.parent.name+"." + task.name]

        // the order of precedence: qubeConfig(qube.yaml) -> toolchain.manifest -> opinion
        def actions = []
        println("found taskDefInProject.actions : " +taskDefInProject?.actions)
        try {

        if (taskDefInProject?.actions) {
            // action arg1 arg2 ...
            println("found taskDefInProject.actions : " +taskDefInProject?.actions)

            for (action in taskDefInProject.actions) {
                // actions.add(action)
                println("found action : " + action)
                actions << action
            }
        } else if (taskInToolchain?.trim()) {
            actions << taskInToolchain
        } else if (task.actions) {
            for (action in task.actions) {
                // actions.add(action)
                actions << action
            }
        }
        if (actions.empty) {
            error ('no action is defined for task: ' + task.name)
        }

        def args = [:]
        int count = 0
        if (taskDefInProject?.args) {
            for (arg in taskDefInProject?.args) {
                count++
                args.put(count, arg)

                println("found args in project : " + arg)                
            }   
        } else if (task.properties) {
            def taskDefaultArgs = task.properties.get("args")
            for (arg in taskDefaultArgs) {
                count++
                args.put(count, arg)
                println("found args in opinion : " + arg)                

            }
        }

        def commands = qubeCommand(
            actions: actions,
            args: args,
            qubeClient: qubeClient,
            globalVariablesMap: projectVariables,
            qubeYamlString: qubeYamlString)
        println(commands.size() + ' command(s) will be run:')
        for (command in commands) {
            println('credentialsMetadata.size(): ' + command.credentialsMetadata?.size());
            qubeship.withQubeCredentials(command.credentialsMetadata) {
                String scriptStmt = command.fullQubeshipCommand
                def statusCode = 0
                if (scriptStmt.contains('docker build ')) {
                    resolvedScriptStmt = scriptStmt.replaceAll("docker build ", "docker build -q ") + " | tee /tmp/${run_id}-buildimage"
                    println(resolvedScriptStmt)
                    statusCode = sh (script: resolvedScriptStmt,returnStatus:true)
                    def imageId = readFile("/tmp/${run_id}-buildimage").trim().tokenize(':').last()
                    dynamicEnvVars["QUBESHIP_IMAGE_ID"] = imageId
                    projectVariables["QUBESHIP_IMAGE_ID"] = new SerializableTuple<ValueWrapper,Endpoint>(new StringValueWrapper(imageId), null);
                } else {
                    dynamicEnvVarsString=""
                    dynamicEnvVarsShellString = ""
                    dynamicEnvVarsDockerString=""
                    for (envEntryKey in dynamicEnvVars.keySet() ) {
                        envEntry=envEntryKey+"="+dynamicEnvVars[envEntryKey]
                        dynamicEnvVarsShellString+="export ${envEntry}; " 
                        //dynamicEnvVarsDockerString+="-e ${envEntry} " 
                    }
                    println("dynamicEnvVarsShellString: ${dynamicEnvVarsShellString}")
                    println("dynamicEnvVarsDockerString: ${dynamicEnvVarsDockerString}")

                    if (executeInToolchain) {
                        scriptStmt = "docker exec ${dynamicEnvVarsDockerString} ${container.id} sh -c \"" + scriptStmt.trim() + "\""
                    } else {
                        scriptStmt="${dynamicEnvVarsShellString} ${scriptStmt}"
                    }
                    println(scriptStmt)
                    statusCode = sh (script: scriptStmt,returnStatus:true)
                }
                println("cmd: " + scriptStmt + " : statusCode: " + statusCode)
                //def statusCode = 0
  
                if (statusCode == 1 ) {
                    currentBuild.result = 'FAILURE'
                    throw new Exception("$scriptStmt returned error code :" + statusCode)
                }
                if(statusCode == 2 ) {
                    currentBuild.result = 'UNSTABLE'
                }

                if (scriptStmt.contains('docker push')) {
                    artifactsImageId = scriptStmt.tokenize(' ').last()
                }
            }
        }
        }finally{
            if (taskDefInProject?.publish && executeInToolchain) {
                for (artifactVal in taskDefInProject.publish) {
                    try {
                        artifactParts=artifactVal.tokenize(':')
                        artifact  = artifactParts[0]
                        File artifactFile = new File(artifact)
                        parentPath = artifactFile.getParent()
                        baseArtifactFileName=artifactFile.getName()
                        println("baseArtifactFileName:" + baseArtifactFileName)

                        println("parentPath :" + parentPath)
                        artifactAlias=baseArtifactFileName
                        if(parentPath) {
                        sh(script:"mkdir -p ./${parentPath}")
                        } else {
                          parentPath="."  
                        }
                        def copyStatement = "docker cp ${container.id}:${workdir}/${artifact} ./${parentPath}"
                        println(copyStatement)
                        
                        sh(script: copyStatement, label:"Transfering artifacts from container")
                        //if (artifactParts.length>1) {
                        //    artifactAlias = artifactParts[1]
                        //}
                        destArtifactName = task.name + "-" + artifactAlias


                        println("alias :" + artifactAlias)
                        
                        if (baseArtifactFileName.endsWith(".html") || baseArtifactFileName.endsWith(".json") ) {
                          publishHTML (target: [
                            allowMissing: false,
                            alwaysLinkToLastBuild: false,
                            keepAll: true,
                            reportDir: parentPath,
                            reportFiles: baseArtifactFileName,
                            reportName: destArtifactName
                          ])
                          artifactToPublish.push(destArtifactName)
                       }  
                    }catch(Exception ex) {
                        ex.printStackTrace()
                    }
                }
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

def prepareDockerFileForBuild(image, id, project_name, workdir) {
    String dockerFile = "Dockerfile-build-" + id
    String imageVersion = "${env.BUILD_NUMBER}"
    
    sh(script: "echo FROM ${image} > ${dockerFile} && \
    echo RUN mkdir -p ${workdir} >> ${dockerFile} && \
    echo WORKDIR ${workdir} >> ${dockerFile} && \
    echo ENV QUBE_BUILD_VERSION=${imageVersion} >> ${dockerFile} && \
    echo ADD . ${workdir} >> ${dockerFile}")

    sh(script: 'cat ' + dockerFile)

    String buiderImageTag = project_name + "-" + id + "-build"
    buiderImageTag = buiderImageTag.replaceAll("\\s+|_+", "-").toLowerCase()
    docker.image(image).pull()
    sh(script: "docker build -t ${buiderImageTag} -f ${dockerFile} .")

    return buiderImageTag
}

def getQubeshipEntity(result) {
    if(!result) {
        throw new Exception("Result is null. cannot convert to qubeship entity")
    }
    if(result.getFirst().getType() in String) {
        throw new Exception("$result is of type String. cannot convert to qubeship entity")
    }
    //def slurper = new groovy.json.JsonSlurper()
    Object entity = net.sf.json.JSONObject.fromObject(result.getFirst().getValue())
    //def entity = slurper.parseText(result.getFirst().getValue())
    return entity
}

def pushPipelineEventMetrics(analyticsEndpoint, eventType, Date timestamp) {
    try {
        if (analyticsEndpoint?.trim()) {
            analyticsEndpoint = analyticsEndpoint[-1] == "/" ? analyticsEndpoint.substring(0, analyticsEndpoint.length() - 1) : analyticsEndpoint
            pipelineMetricsPayload['event_id'] = randomUUID() as String
            pipelineMetricsPayload['event_timestamp'] = timestamp.format('yyyy-MM-dd HH:mm:ss')
            pipelineMetricsPayload['event_type'] = eventType
            def payloadJson = JsonOutput.toJson(pipelineMetricsPayload)
            sh (script: "curl -s -o /dev/null -X PUT ${analyticsEndpoint}/${pipelineMetricsPayload['event_id']}.json "
                + "-H 'cache-control: no-cache' "
                + "-H 'content-type: application/json' "
                + "-d '${payloadJson}'")
        }
    } catch(Exception ex) {
        println("WARNING: unable to log analytic event " + ex.getMessage())
        ex.printStackTrace();
    }
}
