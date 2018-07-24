def project = "ScienceTools"


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

// def images = [ "condaforge-linuxanvil":'condaforge/linux-anvil:latest' ]

try {
  // notifyBuild('STARTED')


  def blessed = 'glast'
  def labels = ['fermi-build01']
  //def labels = ['fermi-build01', 'lsst-build01', 'srs-build01']
  def os_arch_compiler = "redhat6-x86_64-64bit-gcc44"

  def builders = [:]
  for (x in labels) {
    def buildNode = x // Need to bind the label variable before the closure - can't do 'for (label in labels)'
    // Create a map to pass in to the 'parallel' step so we can fire all the builds at once
    builders[buildNode] = {
      node('docker') {
        deleteDir()
        docker.image('fssc/conda').inside{

          // Create a fermi build space using python 2.7 from conda-forge
          stage('Initialize Workspaces') {
            echo "Conda Setup"
            sh "pwd"
            sh "printenv"
            sh "ls -lah /miniconda"
            sh "/miniconda/bin/conda create -n fermi -c conda-forge python=2.7 -y"
            sh "source activate fermi"

            echo "Acquire Conda Recipe"
            sh "git clone https://github.com/fermi-lat/ScienceTools-conda-recipe.git"
            sh "cd ScienceTools-conda-recipe"
          }

          stage('Compile - Conda build'){
            echo "Conda Build"
            sh "/miniconda/bin/conda build -c conda-forge -c fermi_dev_externals . -y"
          }

          stage('Test - fermitools-test-scripts'){
            echo "Install tests from Conda"
            sh "/miniconda/bin/conda install -c fermi_dev_externals fermitools-test-scripts -y"

            echo "Pulsar Tests"
            try { sh "ST-pulsar-test" }
            catch (e) {currentBuild.result = "TEST_FAILURE"}

            echo "AGN tests"
            try { sh "ST-AGN-thread-test-Binned" }
            catch (e) {currentBuild.result = "TEST_FAILURE"}
            try { sh "ST-AGN-thread-test-Unbinned" }
            catch (e) {currentBuild.result = "TEST_FAILURE"}

            echo "PIP Installing pyroot & pyds9"
            // WTH? Someone needs to fix this. These dependencies should not
            // be necessary to run these test
            sh "/miniconda/bin/pip install pyroot pyds9"

            echo "ST-unit test"
            try { sh "ST-unit-test --bit64" }
            catch (e) {currentBuild.result = "TEST_FAILURE"}
          }

          stage('Archive'){
            echo "ARCHIVE NOT IMPLEMENTED. THE BUILD PRODUCTS ARE LOST."
          }
        }
      }
    }
  }
  parallel builders

  stage('validate') {
      node(blessed){ echo "[Validation]" }
  }

  stage('deploy') {
      node(blessed){ echo "[Deployment]" }
  }
} catch (e) {
    // If there was an exception thrown, the build failed
    currentBuild.result = "FAILED"
    throw e
} finally {
    // Success or failure, always send notifications
    // notifyBuild(currentBuild.result)
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
