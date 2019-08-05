def project = "FermiTools-Conda"


properties([
     parameters([
       stringParam(
         name: 'repoman_ref',
         description: 'Branch, Ref or Commit to build'
       )
     ])
   ])

if (!params.repoman_ref){
    echo "Nothing to Build"
    // currentBuild.result = "SUCCESS"
    // return
}

if (params.description){
    currentBuild.description = description
}


try {
  notifyBuild('STARTED')

  def post = new URL("https://dev.azure.com/FermiSpaceTelescope/Fermitools/_apis/build/builds?api-version=5.0")
  def message = "{"definition": { "id": 30 }}"
  post.setRequestMethod("POST")
  post.setDoOutput(true)
  post.setRequestProperty("Content-Type", "application/json")
  post.getOutputStream().write(message.getBytes("UTF-8"));
  def postRC = post.getResponseCode(); 
     
  def blessed = 'glast'
  def labels = ['fermi-build01']
  //def labels = ['fermi-build01', 'lsst-build01', 'srs-build01']
  def os_arch_compiler = "redhat6-x86_64-64bit-gcc44"

  node('docker') {
    deleteDir()
    docker.image('fssc/jenkins-conda:bld04').inside{

      stage('Initialize Workspaces') {
        sh "git clone https://github.com/fermi-lat/Fermitools-conda.git"
        // dir ("Fermitools-conda") {
        //   sh "git checkout ${repoman_ref.split()[0]}"
        // }
      }

      stage('Compile - Conda build'){
        //sh 'scl enable devtoolset-2 "conda build -c conda-forge -c fermi Fermitools-conda"'
        sh 'conda build -c conda-forge/label/cf201901 -c fermi Fermitools-conda'
      }

      stage('Test - fermitools-test-scripts'){
        sh '/bin/bash -c "conda create -n fermi -c conda-forge/label/cf201901 -c fermi --use-local fermitools fermitools-test-scripts -y"'

        try { sh '/bin/bash -c "source activate fermi && ST-pulsar-test"' }
        catch (e) {currentBuild.result = "TEST_FAILURE"}
        try { sh '/bin/bash -c "source activate fermi && ST-unit-test --bit64"' }
        catch (e) {currentBuild.result = "TEST_FAILURE"}

        if (currentBuild.result == "TEST_FAILURE" || currentBuild.result == "FAILURE" || currentBuild.result == "UNSTABLE") {
          echo "Error! Current build result value is: "+currentBuild.result
          error(currentBuild.result)
        }
      }

      stage('Deploy to Anaconda Cloud'){
        withCredentials([string(credentialsId: 'anaconda-fermi-token', variable: 'ANACONDA_TOKEN')]) {
          sh "anaconda -v -t ${ANACONDA_TOKEN} upload -l dev -u fermi /miniconda/conda-bld/linux-64/fermitools*.tar.bz2"
        }
      }
    }
  }

} catch (e) {
    // If there was an exception thrown, the build failed
    currentBuild.result = "FAILED"
    throw e
} finally {
    // Success or failure, always send notifications
    notifyBuild(currentBuild.result)
}


def notifyBuild(String buildStatus = 'STARTED') {
  // build status of null means successful
  buildStatus =  buildStatus ?: 'SUCCESSFUL'

  // Default values
  def colorName = 'RED'
  def colorCode = '#FF0000'
  def subject = "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
  def summary = "${subject} (${env.RUN_DISPLAY_URL})"

  // Override default values based on build status
  if (buildStatus == 'STARTED') {
    color = 'YELLOW'
    colorCode = '#c38b5f'
  } else if (buildStatus == 'SUCCESSFUL') {
    color = 'GREEN'
    colorCode = '#5fba7d'
  } else {
    color = 'RED'
    colorCode = '#C91D2E'
  }

  // Send notifications
  slackSend (color: colorCode,
      channel: "jenkins",
      message: summary,
      teamDomain: "fermi-lat",
      tokenCredentialId: "fermi-lat-slack-token"
      )

  def githubStatus
  switch (buildStatus) {
    case 'STARTED':
      githubStatus = 'PENDING'
      break
    case 'SUCCESSFUL':
      githubStatus = "SUCCESS"
      break
    default:
      githubStatus = 'FAILURE'
  }
  // If this is a commit in a repo in github, notify github
  if (params.sha){
    echo "${params.pkg} sha: ${params.sha}"
    githubNotify (account: 'fermi-lat',
      context: 'Jenkins CI Build',
      credentialsId: 'github.com_slac-glast',
      description: 'CI Build',
      gitApiUrl: '',
      repo: "${params.pkg}",
      sha: "${params.sha}",
      status: githubStatus,
      targetUrl: "${env.RUN_DISPLAY_URL}"
    )
  }
}
