import groovy.json.JsonSlurper 
import java.nio.file.*
import groovy.transform.BaseScript
import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;
import com.cloudbees.plugins.credentials.domains.*;
import java.util.HashMap;
import org.yaml.snakeyaml.Yaml

node {
  def notificationDomain = null
  def notificationChannel = null
  def notificationToken = null
  def notificationType = null
  def qubeConfig = null;
  currentBuild.result = Result.SUCCESS

  try{
    stage 'Qubeship Initializing'

    def isDryRun = "${dry_run}" == "true"
    def isQubeshipProject = "${is_qube_project}" == "true"

    def refspec="${refspec}"
    def commithash="${commithash}"
    if (commithash?.trim().length() == 0)  {
      echo "replacing empty commit hash with refspec " + refspec
      commithash = refspec
    }
    def branch=(refspec?:"master").tokenize('/').last()
    def isCD = true

    credId = '32cd5f88-34c6-4155-83fa-46f5a41a78d6';
    if(!isQubeshipProject) credId = "cred_"+"${qube_project_id}";
    print 'using credId: ' + credId;
    //def qube_project_id = "${qube_project_id}"
    checkout poll:false,scm: [
          $class: 'GitSCM',
          branches: [[name: "${commithash}"]],
          userRemoteConfigs: [[
            url: "${git_repo}",
            credentialsId: credId,
            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*'
          ]]
      ]
   
    gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD', label:"Get Commit id")
     // short SHA, possibly better for chat notifications, etc.
    shorthash = gitCommit.take(6)

    //sh (script: "apt-get install -y python-yaql")
    sh (script: "git clone https://github.com/Qubeship/qube_utils qube_utils",label:"Fetching qubeship scripts and templates")

    def config_file = "qube.yaml"

    def workspace = env.WORKSPACE
    def qubeYamlFile = env.WORKSPACE + '/'+ config_file
    qubeConfig = getQubeConfig(qubeYamlFile);
    
    notifyStarted(qubeConfig)

    def appSvcName = "${appName}"
    def deploymentName="${appSvcName}-deployment"
    def keys_loc=getValueFromConfig(config_file,"security.keys_loc")
    def skipOverlay=!isKeyPresentInConfig(config_file,"security.keys_loc")
    def skipBuild=getBoolValueFromConfig(config_file,"build.skip")
    def skipTest=getBoolValueFromConfig(config_file,"test.skip")
    def skipBake=getBoolValueFromConfig(config_file,"bake.skip")
    def skipDeployment=getBoolValueFromConfig(config_file,"deployment.skip")
    def registryPrefix=""
    def repositories  = new ArrayList();
    def imageVersion="${branch}.${shorthash}.${env.BUILD_NUMBER}"
    if (isDryRun) {
      //appSvcName = "redis"
      imageVersion="latest"

    }
    if(!skipBake) {
        //registryPrefix=getValueFromConfig(config_file,"bake.registry_prefix")
        repositories.addAll(getRepositories(config_file, "bake.repositories", appSvcName, imageVersion))
    }

    QubeshipEnvironments envs = null
    if(!skipDeployment) {
      if (isCD ) {
        envs = getEnvironments(config_file, "deployment.environments", appSvcName, repositories,isQubeshipProject)
      }
    }

    def isContinuousDeployment= getBoolValueFromConfig(config_file,"deployment.continuous_deployment")
    def imageTag = "${appSvcName}:${imageVersion}"
    def bakeDockerfile = ""
    if(!skipBake) {
     bakeDockerfile = getValueFromConfigOptional(config_file,"bake.script","scripts/bake/Dockerfile")
    }

    def dockerFile = ""
    def scriptFile = ""
    def image = ""
    def script = ""
    def publish_artifact = ""
    def publish_artifacts = new ArrayList<String>()
    if(!skipBuild) {
      isDockerFileBasedBuild=isKeyPresentInConfig(config_file,"build.dockerFile")
      isScriptBasedBuild=isKeyPresentInConfig(config_file,"build.script")
      publish_artifact=getValueFromConfigOptional(config_file,"build.publish_artifact","")
      publish_artifacts = getLists(config_file,"build.publish_artifacts", "get artifacts")
      if (publish_artifact?.trim() != "" ) {
          publish_artifacts.add(publish_artifact?.trim());
      }
      if( isDockerFileBasedBuild) {
         dockerFile=getValueFromConfig(config_file,"build.dockerFile")
      } else if(isScriptBasedBuild) {
         scriptFile=getValueFromConfig(config_file,"build.script")
      }else {
         image=getValueFromConfig(config_file,"build.image.name")
         //script=getValueFromConfig(config_file,"build.image.onbuild.0")
         scripts = getLists(config_file,"build.image.onbuild",'Get build scripts')
      }
    }

    if (isDryRun) {
      if(!skipBuild) {
        for(String artifact:publish_artifacts) {
            println (artifact)
        }
      }
      if(!skipBake) {
        debugRepositories(repositories)
      }
      if(!skipDeployment) {
        debugEnvironments(envs)
      }
      return
    }


    if(!skipOverlay) {
      stage 'Secure overlay'
      sh(script:"docker login -e 194749301684-compute@developer.gserviceaccount.com -u oauth2accesstoken -p \"\$(gcloud auth print-access-token)\" https://gcr.io && mkdir -p ${keys_loc}", label: "Authenticating to GCR")
      sh (script:"gsutil cp gs://artifacts.qubeship.appspot.com/keys/keys.tar.gz ${keys_loc}",label:"Fetching secure ceritificate")
      sh (script:"tar xvfz ${keys_loc}/keys.tar.gz -C ${keys_loc}",label:"Installing secure ceritificate on containers")
    }
    
    stage 'Build'
    if(!skipBuild) {
  /*
  # dockerFile: scripts/build/Dockerfile
    image: 
      name: angular-cli:1.0.0-beta.16
      onbuild: 
        - scripts/build/build.sh
    publish_artifact: dist/*

  */
   def mavenSettingsFile = "./settings.xml"

    wrap([$class: 'ConfigFileBuildWrapper', 
            managedFiles: [
                [fileId: '0d1085a9-09c6-4261-bdff-5f2b714cca50', 
                targetLocation: "${mavenSettingsFile}"]]]) {
     
      if(isDockerFileBasedBuild)  {
        sh(script:"docker build -t ${appSvcName}-build -f ${dockerFile} .",label:"Building docker image")
      } else if (isScriptBasedBuild) {
        sh(script:scriptFile,label:"Building docker image")       
      }else {
        dockerFile="Dockerfile-build"
        sh ( script:"echo FROM $image > $dockerFile && \
        echo RUN mkdir -p /home/app >> $dockerFile && \
        echo WORKDIR /home/app >> $dockerFile && \
        echo ADD . /home/app >> $dockerFile",label:"Preparing docker file")
        for(String scriptStmt:scripts) {
          scriptStmt = scriptStmt.trim()
          sh(script:'echo RUN "' + scriptStmt + '" >> '+dockerFile,label:"Preparing docker file")
        }
        sh (script: 'cat ' + dockerFile ,label:"Preparing docker file")
        sh(script:"docker build -t ${appSvcName}-build -f ${dockerFile} .",label:"Building docker image")

      }
    }
      def workdir=sh(returnStdout: true, script: "docker run  ${appSvcName}-build pwd",label:"Build on docker").trim()
      def docker_id=sh(returnStdout: true, script: "docker run -d ${appSvcName}-build",label:"Acquiring container id").trim()
      //def docker_id = sh(returnStdout: true, script: "docker ps -q --filter 'ancestor="+appSvcName+"-build'").trim()
      for (String artifact: publish_artifacts) {
          def copyStatement = "docker cp ${docker_id}:${workdir}/${artifact} ."
          println copyStatement
          sh (script: copyStatement,label:"Transfering artifacts from container")
          if (artifact == "nosetests.xml") {
                step([$class: 'JUnitResultArchiver', testResults: 'nosetests.xml'])
          } 
          if (artifact.contains("_html")) {
              publishHTML (target: [
                allowMissing: false,
                alwaysLinkToLastBuild: false,
                keepAll: true,
                reportDir: artifact,
                reportFiles: 'index.html',
                reportName: "Report " + artifact.replaceAll("/","")
              ])
          } 
          if (artifact=="flake8-output.txt") {
            step([$class: 'WarningsPublisher', parserConfigurations: [[
              parserName: 'flake8', pattern: 'flake8-output.txt'
              ]], unstableTotalAll: '0', usePreviousBuildAsReference: true
            ])
          }
      } 

      //appbuild=sh(returnStdout: true, script: "docker run ${appSvcName}-build ls target").trim()
      //sh("docker run ${appSvcName}-build cat target/$appbuild | tee target/$appbuild > /dev/null")
    }

    stage 'Bake image'
    sh(script:"docker build -t ${imageTag} -f ${bakeDockerfile} .",label:'Baking docker image')
    sh(script:"cat qube_utils/apparmor.log",label:'Container Hardening - App Armor')

    stage 'Deploy to QA'
    sh(script:"echo deploying to QA",label:'Deployment')

    stage 'Run tests'
    if (!skipTest) {
      def functionalTestProvider = getValueFromConfig(config_file,"test.functional.provider")
      testApplication(appSvcName, functionalTestProvider)
    }

    if (isCD && !skipDeployment) {
      stage 'Push image to registry'
      pushImageToRepositories(repositories, imageTag,isQubeshipProject);

      stage 'Container Security Scan'
      sh(script:"cat qube_utils/scan.txt",label:'Static Security Scan - Claire')
      sh(script:"cat qube_utils/scan.txt",label:'Dynamic Security Scan - Twistlock')

      stage 'approval to promote to prod'
      if (!isContinuousDeployment) {
        input 'Are you sure?'
      } else {
        echo "automated release to production  continuous_deployment : $isContinuousDeployment"
      }

      stage "Release to Prod"
      for (ProjectEnvironment env: envs.prodList) {
        println (env.toString())
        def imageToBeDeployed = env.repo.prefix + "/" + imageTag
        deployApp(env, imageToBeDeployed)
      }

      // ************** TO BE REVISITED: for success/rollback each prod environment **************
      //stage "Baseline"
      //baselineDeployment(deploymentNSProd, deploymentName, 500, 'SECONDS')
    }
    notifySuccessful(qubeConfig)
  }catch (e) {
    currentBuild.result = Result.FAILURE
    notifyFailed(qubeConfig)
    throw e
  }
}

def testApplication(appSvcName, functionalTestProvider) {
  if(functionalTestProvider=="saucelabs") {
    testAppFunctionalSauceLabs(appSvcName)
  }else {
    testAppFunctionalLocalGrid(appSvcName)

  }
}

def testAppFunctionalLocalGrid(appSvcName) {
    sh(script:"docker run --rm  -i  -e SELENIUM_USERNAME=\$SAUCE_USERNAME -e SELENIUM_ACCESS_KEY=\$SAUCE_ACCESS_KEY -e SELENIUM_GRID=sg.qubeship.io:80 ${appSvcName}-build ./scripts/test/test-app.sh"
      ,label:"Launching tests on qubeship selenium grid")
    //step([$class: 'SauceOnDemandTestPublisher', testDataPublishers: [[]]])
}

def testAppFunctionalSauceLabs(appSvcName) {
  sauce('qubehyunji') {
    sauceconnect(useGeneratedTunnelIdentifier: true, verboseLogging: true) {
      sh(script:"docker run --rm  -i -e SELENIUM_GRID=ondemand.saucelabs.com -e SELENIUM_USERNAME=\$SAUCE_USERNAME -e SELENIUM_ACCESS_KEY=\$SAUCE_ACCESS_KEY ${appSvcName}-build ./scripts/test/test-app.sh"
        ,label:"Launching test on SauceLabs selenium grid")
      //step([$class: 'SauceOnDemandTestPublisher', testDataPublishers: [[]]])
    }
  }
}


def baselineDeployment(deploymentNS, deploymentName, waitTime, waitTimeUnits) {
  timeout(time: waitTime, unit: waitTimeUnits) {
        try {
          choice = new ChoiceParameterDefinition('', ['Success', 'Rollback'] as String[], 'Description')
          def ui = input (message: 'Select Deployment Status', parameters: [choice]);
          if ( ui== "Rollback" )  {
            sh(script:"kubectl --namespace=${deploymentNS} rollout undo deployment/${deploymentName}")
            echo "Deployment successfully rolled back"

          }else {
            echo "Deployment successful"
          }
        } catch (InterruptedException _x) {
          x = _x // rejected
          echo "Deployment baseline timed out"

        }
    }
}

def deployApp(projectEnvironment, imageToBeDeployed) {
  def processingType = projectEnvironment.processingType

  if (processingType == "qubeship_managed") {
    deployViaQubeship(projectEnvironment, imageToBeDeployed)
  } else if (processingType == "custom") {
    if (projectEnvironment.targetEnvironment.provider == "k8s" ) {
      deployCustomToK8S(projectEnvironment, imageToBeDeployed)
    }
  }
}

def deployViaQubeship(projectEnvironment, imageToBeDeployed) {
  def provider = projectEnvironment.targetEnvironment.provider

  if (provider == "k8s" || provider == "kubernetes") {
    deployQubeToK8S(projectEnvironment, imageToBeDeployed)
  }
  else if (provider == "ucp") {
    //deployQubeToUCP(projectEnvironment)
  }
}

def deployQubeToK8S(projectEnvironment, imageToBeDeployed) {
  def environment = projectEnvironment.envCategory
  def appName = projectEnvironment.appName
  def deploymentName = "${appName}-deployment"
  def deploymentTemplate = projectEnvironment.templateId
  def deploymentNS = projectEnvironment.targetEnvironment.namespace
  def envId = projectEnvironment.targetEnvironment.id
  def provider = projectEnvironment.targetEnvironment.provider
  def tenant = "qubeship" //TODO: Clean up
  def endpoint = projectEnvironment.targetEnvironment.endpoint
  def workspace = env.WORKSPACE?.trim()
  deploymentBaseName="deploymentTemplate-${environment}-${deploymentNS}-${deploymentName}-${env.BUILD_NUMBER}"
  deploymentArtifactsFolder="/tmp/deployment/${deploymentBaseName}"
  
 def credentialId = projectEnvironment.targetEnvironment.credentialId
  withCredentials([[$class: 'StringBinding', credentialsId: credentialId, variable: 'ACCESS_TOKEN']]) {
    sh (script:
    "qube_utils/deployment_templates/k8s/qube_deploy_k8s.sh  " + 
    "$imageToBeDeployed $deploymentNS $appName $deploymentName $deploymentArtifactsFolder " + 
    " $deploymentTemplate $workspace ${environment} ${envId} ${tenant} ${provider} ${endpoint} $env.ACCESS_TOKEN",
    label:"Deploying to kubernetes using qubeship template")
  }
}

def deployQubeToUCP(projectEnvironment, imageToBeDeployed) {
  /* common */
  def environment = projectEnvironment.envCategory
  def appName = projectEnvironment.appName
  def deploymentName = "${appName}-deployment"
  def deploymentTemplate = projectEnvironment.templateId

  /* UCP-specific */
  def controller = projectEnvironment.targetEnvironment.controller
  def credentialId = projectEnvironment.targetEnvironment.credentialId
  def endpointName = projectEnvironment.targetEnvironment.endpointName != "none"
    ? projectEnvironment.targetEnvironment.endpointName : appName
  def endpointDomain = projectEnvironment.targetEnvironment.domain
  def swarmHost = projectEnvironment.targetEnvironment.swarmManager

  withDockerServer([credentialsId: credentialId, uri: controller]) {
    deploymentBaseName = "deploymentTemplate-ucp-${environment}-${appName}-${env.BUILD_NUMBER}"
    deploymentArtifactsFolder="/tmp/deployment/${deploymentBaseName}"
    sh (script:
      "qube_utils/deployment_templates/docker/qube_deploy_ucp.sh" + " " + 
      "$imageToBeDeployed $appName $endpointName $endpointDomain $swarmHost $deploymentArtifactsFolder $deploymentTemplate $deploymentName")
  }
}

def deployCustomToK8S(projectEnvironment, imageToBeDeployed) {
  def endpoint = projectEnvironment.targetEnvironment.namespace
  def deploymentNS = projectEnvironment.targetEnvironment.namespace
  def credentialId = projectEnvironment.targetEnvironment.credentialId

  withCredentials([[$class: 'StringBinding', credentialsId: credentialId, variable: 'ACCESS_TOKEN']]) {
   sh(script:"kubectl version")
   sh(script:"sed -i.bak 's#%gcr.io/image:version%#${imageToBeDeployed}#' ./k8s/production/*.yaml",label:"Preparing kuberenetes artifacts")
   sh(script:"kubectl --namespace=${deploymentNS} apply -f k8s/services/ --server=${endpoint} --token $env.ACCESS_TOKEN  --record",label:"Deploying pods to kuberenetes")
   sh(script:"kubectl --namespace=${deploymentNS} apply -f k8s/production/ --server=${endpoint} --token $env.ACCESS_TOKEN --record",label:"Deploying service artifact to kuberenetes")
  }
}

@NonCPS
def isSet(text) {
  def result =  (text.trim() ?: "false" ).equals("true");
  result
}

def getValueFromConfigOptional(file, field , defaultVal) {
  try {
  if (!isKeyPresentInConfig(file, field)) {
    return defaultVal
  }
  def parse_statement="jq -r .${field}"

  if(file.endsWith(".yaml")) {
    parse_statement="shyaml get-value ${field} ${defaultVal}"
  }
  
  def result = sh ( returnStdout: true, script: "cat $file | $parse_statement ",label:'Read yaml config').split("\r?\n")[0]
  //println  "getValueFromConfigOptional(" + file + "," + field + "," + defaultVal+") : " + result.trim() + "-"
  result?.trim()
  }catch(Exception ex) {
    println "exception ex" + ex
    defaultVal
  }
}

def getValueFromConfig(file, field) {
   def parse_statement="jq -r .${field}"
   if(file.endsWith(".yaml")) {
      parse_statement="shyaml get-value ${field}"
   }
   def result = sh ( returnStdout: true, script: "cat $file | $parse_statement ",label:'Read yaml config')
   result
}
def getKeyLengthFromConfig(file, field) {
    if(file.endsWith(".yaml")) {
      parse_statement=""
   } 
}
def isKeyPresentInConfig(file, field) {
   def parse_statement="jq -r .${field}"
   if(file.endsWith(".yaml")) {
      parse_statement="shyaml get-value ${field} '##dontexist##'"
   }
   def result = sh ( returnStdout: true, script: "cat $file | $parse_statement ",label:'Read yaml config')
   if(file.endsWith(".yaml")) {
     result != "##dontexist##"
   }else {
     result!=''    
   }
}
def getBoolValueFromConfig(file, field) {
   def parse_statement="jq -r .${field}"
   if(file.endsWith(".yaml")) {
      parse_statement="shyaml get-value ${field} '##dontexist##'"
   }
   def result = sh ( returnStdout: true, script: "cat $file | $parse_statement ",label:'Read yaml config').toLowerCase()
   if(result == "##dontexist##") {
    result = "false"
   }
   isSet(result)
}

class Repository implements Serializable {
  String name;
  String prefix;
  String type;
  String repo_name;
  String imageVersion;
  String tag;
  static final long serialVersionUID = 1L;

  Repository (String name, String prefix, String type, String repo_name, String imageVersion) {
    this.name = name;
    this.prefix=prefix;
    this.type = type;
    this.repo_name = repo_name;
    this.imageVersion = imageVersion;

    this.tag = this.prefix + "/" + this.repo_name + ":" + this.imageVersion;
  }

  def String toString() {
     return "name: $name / prefix: $prefix / type: $type / repo_name: $repo_name / imageVersion: $imageVersion / tag: $tag";
  }
}
def getLists(String config_file , String key, String stepDescription) {
    
    ArrayList<String> list = new ArrayList<String>();
    try {
      if( !(isKeyPresentInConfig(config_file, key))) 
      {
        return list
      }

      parse_statement="./qube_utils/scripts/yaql_parser.py --file $config_file --expression '\$."+ key + ".len()'"
      def int length = Integer.parseInt(sh ( returnStdout: true, script: "$parse_statement",label: stepDescription))
      
      if(length > 0) {
          for (int index = 0; index < length; index++ ) {
              def item=getValueFromConfig(config_file, "${key}.${index}")
              println ("value in list is " + item)           
              list.add(item);
          }
      }
    }catch(Throwable ex ) {
      println "Exception in getList " + ex
    }
    println "in getLists: " + list
    return list;
}

def getRepositories(String config_file , String key, String app_name, String imageVersion) {
    
    def repoList = new ArrayList();
  //  sh (script: "pwd")
    parse_statement="./qube_utils/scripts/yaql_parser.py --file $config_file --expression '\$."+ key + ".len()'"
    def int length = Integer.parseInt(sh ( returnStdout: true, script: "$parse_statement",label:'Get repository config'))
    if(length > 0) {
        for (int index = 0; index < length; index++ ) {
            def prefix=getValueFromConfig(config_file, "${key}.${index}.prefix")      
            def name=getValueFromConfig(config_file, "${key}.${index}.name")      
            def type=getValueFromConfig(config_file, "${key}.${index}.type")   
            def repo_name=getValueFromConfigOptional(config_file, "${key}.${index}.repo_name",app_name)
            Repository repo = new Repository (name, prefix, type, repo_name, imageVersion);
            Repository repoLatest = new Repository (name, prefix, type, repo_name, "latest");
            repoList.add(repo);
            repoList.add(repoLatest);
        }
    }
    return repoList;
}

def pushImageToRepositories(ArrayList repositories, String imageTag, boolean isQubeshipProject) {
  def branches = [:]
  for (Repository repo: repositories) {
    if (repo.type == "gcr") {
      pushImageToGCR(repo, imageTag,isQubeshipProject)
    } 
    else if (repo.type == "dtr") {
      pushImageToDTR(repo, imageTag)
    }
  }
}

def pushImageToGCR(Repository repo, String imageTag, boolean isQubeshipProject) {
  println "pushImageToGCR: "+ repo.name + " :" + repo.tag +" qubeshipRegistry: " + isQubeshipProject
  def result = sh (returnStdout: true, script: "docker tag ${imageTag} ${repo.tag}",label:"Preparing image for docker push")
  if(isQubeshipProject){
    withDockerRegistry([credentialsId: 'gcr:qubeship', url: 'https://gcr.io']) {
      sh (script: "docker push ${repo.tag}",label:"Pushing image to docker repository")
    }
  } else {
    withDockerRegistry([credentialsId: 'gcr:qubeship-partners', url: 'https://gcr.io']) {
      sh (script: "docker push ${repo.tag}",label:"Pushing image to docker repository")
    }
  }
}

def authenticateGKE(String credentialId, String clusterName, String computeZone, boolean dolegacyAuth ) {
    withCredentials([[$class: 'FileBinding', credentialsId: 'qubeship-kubernetes-secret', variable: 'qube_kubernetes']]) {
        def GOOGLE_AUTH_EMAIL = sh (returnStdout: true, script: "cat $env.qube_kubernetes | jq .client_email").replaceAll("\"", "").replaceAll("\n","")
        def GOOGLE_PROJECT_ID = sh (returnStdout: true, script: "cat $env.qube_kubernetes | jq .project_id").replaceAll("\"", "").replaceAll("\n","")
        //println GOOGLE_AUTH_EMAIL + "," +  GOOGLE_PROJECT_ID
        sh  (script:"gcloud auth activate-service-account $GOOGLE_AUTH_EMAIL --key-file $env.qube_kubernetes && \
         gcloud config set project $GOOGLE_PROJECT_ID && \
         gcloud config set compute/zone $computeZone && \
         gcloud config set container/cluster $clusterName",label:"Authenticating to kubernetes")
        if(dolegacyAuth) {
          sh  (script:"gcloud config set container/use_client_certificate True")
        }
        sh  (script:"gcloud container clusters get-credentials $clusterName",label:"Initializing deployment environment")
    }
}

def pushImageToDTR(Repository repo, String imageTag) {
  def repoName = repo.repo_name;
  def tarToBeSavedAndLoaded = "${repoName}-${env.BUILD_NUMBER}.tar"
  println "pushImageToDTR: new tag) " + repo.tag
  println "pushImageToDTR: tar file) " + tarToBeSavedAndLoaded
  def img = docker.image(imageTag)
  img.tag(repo.tag, true, true)
  img.save(repo.tag, tarToBeSavedAndLoaded)
  withDockerServer([credentialsId: 'docker-ucp-credentials', uri: 'tcp://ucp.hippocamp.io:443']) {
    docker.withRegistry('https://${repo.prefix}/', 'dtr-admin-credential') {
      img.load(tarToBeSavedAndLoaded)
      img.push(repo.tag, true, true)
    }
  }
}

class TargetEnvironment implements Serializable {
   String provider;
   String id;
   String credentialId;
   String endpoint;

   private static final long serialVersionUID = 3526473395612776159L;

   TargetEnvironment(String provider, String id, String credentialId, String endpoint) {
    this.provider = provider;
    this.id = id;
    this.credentialId = credentialId;
    this.endpoint = endpoint;
   }
}

class UCPTargetEnvironment extends TargetEnvironment {
  String endpointName
  String domain;
  String controller;
  String swarmManager;
  static final long serialVersionUID = 1L;

  UCPTargetEnvironment(String provider, String id, String credentialId, String endpoint,
                       String domain, String controller, String swarmManager, String endpointName) {
    super(provider, id, credentialId, endpoint);

    this.domain = domain;
    this.controller = controller;
    this.swarmManager = swarmManager;
    this.endpointName = endpointName;
  }
}

class K8STargetEnvironment extends TargetEnvironment {
   String namespace = "default";
   static final long serialVersionUID = 1L;

  K8STargetEnvironment(String provider, String id, String credentialId, String endpoint,
                       String namespace) {
    super(provider, id, credentialId, endpoint);
    if(namespace != null){
      println "Namespace is "+ namespace
      this.namespace = namespace;
    }
  }
}

class QubeshipEnvironments implements Serializable {
  ArrayList<ProjectEnvironment> qaList = new ArrayList<ProjectEnvironment>();
  ArrayList<ProjectEnvironment> stageList = new ArrayList<ProjectEnvironment>();
  ArrayList<ProjectEnvironment> prodList = new ArrayList<ProjectEnvironment>();
  static final long serialVersionUID = 1L;
}

class ProjectEnvironment implements Serializable {
  String uid;
  String processingType;
  String templateId;
  def targetEnvironment

  String envCategory;
  String appName;
  Repository repo;

  static final long serialVersionUID = 1L;

  ProjectEnvironment (String uid, String processingType, String templateId, String envCategory, String appName, Repository repo) {
     this.uid = uid;
     this.processingType = processingType;
     this.templateId = templateId;
     this.envCategory = envCategory;
     this.appName = appName;
     this.repo = repo;
     //targetEnvironment = lookupEnvironment(uid, envCategory)
  }

  def String toString() {
    def repoName = repo == null ? "n/a" : repo.name
     return "uid: $uid / processingType: $processingType / templateId: $templateId / envCategory: $envCategory / provider: $targetEnvironment.provider / appName: $appName / repoName: $repoName / credentialId: ";
  }
}

def TargetEnvironment lookupQubeshipTargetEnvironment(String id, String envCategory) {
  //talk to consul , and get the environment, parse and create the appropriate type of object based on the provider type 
try{
  TargetEnvironment te = null;
  def key = "qubeship/envs/" + envCategory + "/" + id;
  sh (script: "echo $key")

  def envVars = Jenkins.instance.getGlobalNodeProperties()[0].getEnvVars()
  def consul_server_addr = 'https://' + envVars['CONF_SERVER_ADDR']
  sh (script: "echo $consul_server_addr")

  withCredentials([[$class: 'StringBinding', credentialsId: 'consul_token', variable: 'CONSUL_TOKEN']]) {
    wrap ( [$class: 'ConsulKVReadWrapper' , 
            reads: [ [ debugMode: 'ENABLED', envKey: 'env_info', key: key, token: env.CONSUL_TOKEN, aclToken: env.CONSUL_TOKEN,
                      url: consul_server_addr, ignoreGlobalSettings: true, hostUrl: consul_server_addr]] 
    ]){
      if (env.env_info == null) {
        throw new RuntimeException ("consul error - error looking up environment")
      }
      def envInfo = env.env_info
      sh (script:"cat > env.yaml <<EOL\n" + envInfo + "\nEOL")

      def credentialId = getValueFromConfigOptional("env.yaml", "credential_id", "none")
      def provider = getValueFromConfigOptional("env.yaml", "provider", "none")
      def endpoint = getValueFromConfigOptional("env.yaml", "endpoint", "none")
      switch (provider) {
        case "k8s":
          def namespace = getValueFromConfigOptional("env.yaml", "namespace", "none")
          te = new K8STargetEnvironment(provider, id, credentialId, endpoint, namespace)

          break;
        
        case "ucp":
          def domain = getValueFromConfigOptional("env.yaml", "domain", "none")
          def controller = getValueFromConfigOptional("env.yaml", "controller", "none")
          def swarmManager = getValueFromConfigOptional("env.yaml", "swarm_manager", "none")
          def endpointName = getValueFromConfigOptional("env.yaml", "endpoint_name", "none")

          te = new UCPTargetEnvironment(provider, id, credentialId, endpoint, domain, controller, swarmManager, endpointName)

          break;
        
        default:
          te = new TargetEnvironment(provider, id, credentialId,endpoint)

          break;
      }

      sh (script:"rm -Rf env.yaml")
    }
  }

  return te
  }catch(Exception ex) {
    throw new RuntimeException ("Unable to lookup environment in consul. Requested Environment doesnt exist" , ex)
  }
}

def TargetEnvironment lookupCustomerEnvironment(String id, String envCategory) {
  //talk to consul , and get the environment, parse and create the appropriate type of object based on the provider type 

  TargetEnvironment te = null;
  withCredentials([[$class: 'StringBinding', credentialsId: 'qube-token', variable: 'QUBE_TOKEN']]) {
   
    tenantId = "${qube_tenant_id}"
    orgId = "${qube_org_id}"
    def body = '{"org": "'+orgId+'"}';
    authResponse = httpRequest acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: body,
     url: "${QUBE_SERVER}/v1/auth/exchangeOrgToken", customHeaders:[[name:"Authorization", value:"$env.QUBE_TOKEN"],
     [name:"X-Qube-Tenant", value:tenantId]]

    if(authResponse.status != 200 || authResponse.headers['X-Qube-Auth-Token']?.size() == 0){
       throw new RuntimeException ("Unable to perform effective org operation for master. AuthStatus:"+ authResponse.status)
    }

    orgToken = authResponse.headers['X-Qube-Auth-Token'][0]
    response = httpRequest url: "${QUBE_SERVER}/v1/endpoints/${id}", customHeaders:[[name:"Authorization", value:"${orgToken}"]]
    
    if(response.status != 200){
       throw new RuntimeException ("Unable to lookup endpoint id "+id+ " ApiStatus: "+response.status);
    }

    def result = new JsonSlurper().parseText(response.content)
    
    if(result.credentialId == null){
       throw new RuntimeException ("Credential is not provisioned for the environment "+id)
    }

    switch (result.provider) {
      case "kubernetes":
         credentialId = "qubeship:" + result.category + ":" + result.credentialPath;
         print "querying env api with id"+id + "," + credentialId
         if(response.status ==200){
           te = new K8STargetEnvironment(result.provider, id, credentialId, result.endPoint, result.additionalInfo.namespace)
        }
        break;  
      default:
        throw new RuntimeException ("Unsupported endpoint provider "+result.provider);
        break;
    }
  }

  return te
}

@NonCPS
def getRepoMap(ArrayList<Repository> repositories) {
  HashMap<String, Repository> repoMap = new HashMap<>()

  for (Repository repo : repositories) {
    repoMap.put(repo.name, repo)
  }

  return repoMap
}

def getEnvironments(String config_file, String key, String appName, ArrayList<Repository> repositories, boolean isQubeshipTarget) {
    QubeshipEnvironments qe = new QubeshipEnvironments()
    def envCategories = ["qa", "stage", "prod"]
    HashMap<String, Repository> repoMap = getRepoMap(repositories)

    for (String envCategory : envCategories ) {
      def envkey = key + "." + envCategory;
      ArrayList<ProjectEnvironment> pes = new ArrayList<ProjectEnvironment>();
      def envPresent=isKeyPresentInConfig(config_file,envkey)
      if(!envPresent) {
        continue
      }
      parse_statement="./qube_utils/scripts/yaql_parser.py --file $config_file --expression '\$."+ envkey + ".len()'"
      def int length = Integer.parseInt(sh ( returnStdout: true, script: "$parse_statement"))

      if (length > 0) {
        for (int index = 0; index < length; index++ ) {
          def id = getValueFromConfig(config_file, "${envkey}.${index}.id")
          def type = getValueFromConfig(config_file, "${envkey}.${index}.type")
          def tmplId = getValueFromConfig(config_file, "${envkey}.${index}.template_id")
          def srcRepo = getValueFromConfig(config_file, "${envkey}.${index}.srcRepo")
          def repo = repoMap.get(srcRepo)

          ProjectEnvironment pe = new ProjectEnvironment(id, type, tmplId, envCategory, appName, repo)
          if(isQubeshipTarget)
            pe.targetEnvironment = lookupQubeshipTargetEnvironment(id, envCategory)
          else
            pe.targetEnvironment = lookupCustomerEnvironment(id, envCategory)
          pes.add(pe)

        }
      }
      switch(envCategory) {
        case "qa":
          qe.qaList.addAll(pes);
        break;

        case "stage":
          qe.stageList.addAll(pes);
        break;

        case "prod":
          qe.prodList.addAll(pes);
        break;
      }

      //envsMap.put(envCategory, pes)

    }
    return qe;
}

def debugRepositories(repositories) {
  println "************ All target repositories ************"
  for (Repository repo: repositories) {
    println (repo.toString())
  }
  println "************ end of target repositories ************"
}

def debugEnvironments(envs) {
  println "############ All target environments ############"
    for (ProjectEnvironment env: envs?.qaList) {
      println (env.toString())
    }

    for (ProjectEnvironment env: envs?.stageList) {
      println (env.toString())
    }

    for (ProjectEnvironment env: envs?.prodList) {
      println (env.toString())
    }
    println "############ end of target environments ############"
}

def notifyStarted(qubeConfig) {
  def msg = "Iteration ${env.JOB_NAME} # ${env.BUILD_NUMBER}  STARTED"
  if (qubeConfig.notification?.type?.trim() == "slack") {
     notifySlack("#FFFF00",msg, qubeConfig)
  }
}

def notifySuccessful(qubeConfig) {
  def msg = "SUCCESS - Iteration  ${env.JOB_NAME} # ${env.BUILD_NUMBER} sucessfully deployed to https://try.qubeship.io"
  if (qubeConfig.notification?.type?.trim() == "slack") {
      notifySlack("#00FF00",msg, qubeConfig)
  }
}
@NonCPS
def getCurrentBuild () {
  def job = Jenkins.instance.getItemByFullName(env.JOB_BASE_NAME, hudson.model.Job.class)
  return job?.getBuildByNumber(Integer.parseInt(env.BUILD_NUMBER))
 
}
def notifyFailed(qubeConfig){
  def msg =  currentBuild?.result?.toString() + " - Iteration ${env.JOB_NAME} # ${env.BUILD_NUMBER} "  
  if (qubeConfig.notification?.type?.trim() == "slack") {
       notifySlack("#FF0000", msg,  qubeConfig)
  }
}

def notifySlack(color, message, qubeConfig) {
  domain =  qubeConfig.notification?.domain
  channel = qubeConfig.notification?.channel

  
  if (domain == null || 
    domain?.trim().equals("null") || 
    domain?.trim().length() == 0 ) {
    println message
    return
  }
  credKey = "slack." + domain + "." + channel 
  withCredentials([[$class: 'StringBinding', credentialsId: credKey, variable: 'NOTIFICATION_TOKEN']]) {
    slackSend (color: color, message: "$message ${env.JOB_NAME} # ${env.BUILD_NUMBER} ",
      channel: "#" + channel, teamDomain: domain, token:env.NOTIFICATION_TOKEN)
  }
}



def getQubeConfig(file) {
  def result = sh(returnStdout: true, script: "cat $file");
  def yaml = new Yaml();
  def config = yaml.load(result) ;
  initValidateQubeConfig(config)

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
