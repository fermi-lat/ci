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
    currentBuild.result = "SUCCESS"
    return
}

if (params.description){
    currentBuild.description = description
}

try {
    notifyBuild('STARTED')

    def blessed = 'glast'
    def labels = ['fermi-build01']
    //def labels = ['fermi-build01', 'lsst-build01', 'srs-build01']

    stage('Initialize Workspaces') {
        def builders = [:]
        for (x in labels) {
            def buildNode = x // Need to bind the label variable before the closure - can't do 'for (label in labels)'
            // Create a map to pass in to the 'parallel' step so we can fire all the builds at once
            builders[buildNode] = {
                node(buildNode) {
                    sh "source /scratch/bvan/repoman-env/bin/activate && repoman checkout --force --develop ${project} ${repoman_ref}"
                }
            }
        }
        parallel builders
    }

    stage('Compile and Test') {
        def builders = [:]
        for (x in labels) {
            def buildNode = x // Need to bind the label variable before the closure - can't do 'for (label in labels)'
            // Create a map to pass in to the 'parallel' step so we can fire all the builds at once
            builders[buildNode] = {
                node(buildNode) {
                    def os_arch_compiler = "redhat6-x86_64-64bit-gcc44"
                    def artifact_name = "${JOB_BASE_NAME}-${BUILD_NUMBER}-${os_arch_compiler}"
                    def glast_ext = "/afs/slac/g/glast/ground/GLAST_EXT/${os_arch_compiler}"

                    echo "[Build]"
                    // sh """/afs/slac/g/glast/applications/SCons/2.1.0/bin/scons \
                    //     -C ${project} \
                    //     --site-dir=../SConsShared/site_scons \
                    //     --with-GLAST-EXT=${glast_ext}\
                    //     all
                    // """

                    echo "[Test]"

                    // Pull test names from SConscript target names. Thus do not expect that
                    // tests were build for certain.
                    // def testTargets = sh(script:"""
                    //     grep '^\\s*test_\\w* = progEnv\\.Program(' **/SConscript | cut -d "'" -f2
                    // """, returnStdout:true).split('\n')

                    def testTargets = readYaml file:"ScienceTools/testList.yaml"

                    withEnv(["GLAST_EXT=${glast_ext}"]){
                        // sh "bin/${os_arch_compiler}/test_Likelihood"
                        for (test in testTargets) {
                          sh "bin/${os_arch_compiler}/${test}"
                        }
                    }
                    sh """
                        mkdir ${artifact_name}
                        cp -r bin/${os_arch_compiler} ${artifact_name}/bin
                        cp -r exe/${os_arch_compiler} ${artifact_name}/exe
                        cp -r lib/${os_arch_compiler} ${artifact_name}/lib
                        cp -r data ${artifact_name}/data
                        cp -r include ${artifact_name}/include
                        cp -r python ${artifact_name}/python
                        cp -r syspfiles ${artifact_name}/syspfiles
                        cp -r xml ${artifact_name}/xml
                        tar czf ${artifact_name}.tar.gz ${artifact_name}
                    """
                    archive "${artifact_name}.tar.gz"
                    sh """ rm *.fits *.dat *.log *.txt *.lc *.ref *.out *.pha
                        *.root *.par *.rsp *.tpl *.xml *.ccube *.healcube *-out
                    """
                }
            }
        }
        parallel builders
    }

    stage('validate') {
        node(blessed){
            echo "[Validation]"
        }
    }

    stage('deploy') {
        node(blessed){
            echo "[Deployment]"
            //sh 'cp /nfs/slac/g/glast/ground/containers/singularity/containers.master.img.gz .'
            //archive 'containers.master.img.gz'
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
