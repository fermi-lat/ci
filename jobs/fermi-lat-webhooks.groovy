import hudson.model.*

def description
properties([
     parameters([
       stringParam(
         description: 'Github Payload',
         name: 'payload'
       )
     ])
   ])


def projects = ["ScienceTools", "GlastRelease"]
def projectsToBuild = []

stage('Parse Webhook') {
    def ref
    def sha
    def pkg
    node("glast"){
        sh "rm -rf *"
        def payloadObject = readJSON text: payload
        def eventType = ""
        // Only trigger on pull requests that are opened, edited, or reopened
        if( "pull_request" in payloadObject && payloadObject.action in ["opened", "edited", "reopened"]) {
            eventType = "pull_request"
            ref = payloadObject.pull_request.head.ref
            sha = payloadObject.pull_request.head.sha
            description = "<a href='${payloadObject.pull_request.html_url}'>PR #${payloadObject.pull_request.number} - ${payloadObject.pull_request.head.repo.name}"
            currentBuild.description = description
        } else if ("pusher" in payloadObject){
            eventType = "push"
        } else {
            currentBuild.result = 'SUCCESS'
            return
        }
        def login = payloadObject.sender.login
        pkg = payloadObject.repository.name

        if (pkg in projects){
            projectsToBuild.add(pkg)
        } else {
            for (project in projects){
                sh "git clone git@github.com:fermi-lat/${project}.git"
                def statusCode = sh script:"cat ${project}/packageList.txt | grep '^${pkg}'", returnStatus:true
                echo "Return: ${statusCode}"
                if (statusCode == 0){
                    projectsToBuild.add(project)
                }

            }
        }
    }
    echo "Building:"
    echo "${projectsToBuild}"
    for (project in projectsToBuild){
        def job = "${project}-CI"
        def build = build (job: job,
          parameters: [
              [$class: 'StringParameterValue', name: 'repoman_ref', value: ref],
              [$class: 'StringParameterValue', name: 'sha', value: sha],
              [$class: 'StringParameterValue', name: 'pkg', value: pkg]
            ],
            wait:false
        )
    }
}

@NonCPS
def slurpJson(String data) {
  def slurper = new groovy.json.JsonSlurper()
  slurper.parseText(data)
}

