def project = "GlastRelease"

properties([
     parameters([
       stringParam(
         name: 'repoman_ref',
         description: 'Branch, Ref or Commit to build'
       ),
       booleanParam(
         name: 'develop',
         defaultValue: true,
         description: 'Perform builds against development branches (master)'
       ),
       booleanParam(
         name: 'release',
         defaultValue: false,
         description: 'Perform a release (can't be executed with `develop`)'
       )
     ])
   ])

def ref_split = repoman_ref.split(" ")
def initial_ref = ref_split[0]
def is_release_tag =  initial_ref.contains("refs/tags/") && ref_split.length == 1
def release_tag = is_release_tag ? initial_ref.split("/")[2] : null

// We only deploy if this is tagged and we are asking for a release
def is_release = is_release_tag && release

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

    def variants = ['rhel6-x86_64-64bit-gcc44']

    def images = [
        "rhel6-x86_64-64bit-gcc44":'fermilat/base:centos6-py27-gcc44',
    ]

    def builders = [:]
    for (x in variants) {
        def variant = x // Need to bind the label variable before the closure - can't do 'for (label in labels)'
        builders[variant] = {
            node('docker') {
                deleteDir()
                docker.image(images[variant]).inside {
                    stage('Initialize Workspace') {
                        
                        // Setup system things so that we play nice with SSH and pip
                        if(!fileExists("/home/centos/.local/lib64")){
                            sh "mkdir -p /home/centos/.local/lib"
                            sh "ln -s /home/centos/.local/lib /home/centos/.local/lib64"
                        }

                        // Get cached source code
                        sh "curl -O https://srs.slac.stanford.edu/hudson/job/GlastReleaseCacher/lastSuccessfulBuild/artifact/GlastRelease.src.tar.gz"
                        sh "tar xzf GlastRelease.src.tar.gz"
                        sh "mv src/* . && rmdir src && rm GlastRelease.src.tar.gz"

                        def develop_opt = develop ? "--develop" : ""
                        // Update the source code
                        sh "pip install scons fermi-repoman numpy"
                        sshagent (credentials: ['glast.slac.stanford.edu']) {
                            sh "repoman checkout ${project} ${develop_opt} ${repoman_ref}"
                        }

                        // Verify everything is in order if this looks like a release
                        if (is_release){
                            def git_tag = sh(returnStdout: true, script: "cd ${project} && git describe --exact-match --tags HEAD").trim()
                            assert git_tag == release_tag
                        }
                
                        // Setup externals (needs scons internally)
                        sh "bash GlastRelease/bootstrap_externals.sh ${WORKSPACE}/externals"
                    }
                    
                    stage('Compile and Test') {
                        def artifact_name = "${JOB_BASE_NAME}-${BUILD_NUMBER}-${variant}"
                        sh """scons -j 5 \
                                  -C ${project} \
                                  --site-dir=../SConsShared/site_scons \
                                  --compile-opt --compile-debug --variant="NONE" \
                                  --with-GLAST-EXT=${WORKSPACE}/externals"""
    
                        // Make developer tarball (no container directory)
                        sh """scons \
                                  -C ${project} \
                                  --site-dir=../SConsShared/site_scons \
                                  --devel-release=${artifact_name}-devel.tar.gz
                        """

                        // Make build tarball (with container directory)
                        sh """
                            mkdir ${artifact_name}
                            echo ${BUILD_URL} > ${artifact_name}/BUILD_URL
                            mv bin ${artifact_name}/bin
                            mv data ${artifact_name}/data
                            mv exe ${artifact_name}/exe
                            mv include ${artifact_name}/include
                            mv jobOptions ${artifact_name}/jobOptions
                            mv lib ${artifact_name}/lib
                            mv python ${artifact_name}/python
                            mv xml ${artifact_name}/xml
                            tar czf ${artifact_name}.tar.gz ${artifact_name}
                        """
                        
                        // Make release tarball (rename directory)
                        if (is_release) {
                            sh """
                                mkdir ${variant}
                                mv ${artifact_name} ${variant}/${release_tag}
                                cd ${variant}
                                tar czf ${release_tag}.tar.gz ${release_tag}
                            """
                        }
                        
                        archiveArtifacts "${artifact_name}.tar.gz"
                        if (is_release) {
                            archiveArtifacts "${variant}/${release_tag}.tar.gz"
                        }
                        // Archive SCons-generated tarball
                        archiveArtifacts "SConsShared/${artifact_name}-devel.tar.gz"
                    }
                }
            }
        }
    }
    parallel builders
    
    // Execute deploy on glast node (NFS access)
    node ('glast') {
    
        stage('validate') {
            echo "[Validation]"
        }
    
        for (x in variants) {
	    // Need to bind the label variable before the closure - can't do 'for (label in labels)'
            def variant = x
            builders[variant] = {
                stage('deploy') {
                    echo "[Deployment]"
                    if (is_release) {
                        echo "Deploying release for ${variant}"
                        deleteDir()
                        // Stage to NFS
                        def target_dir = "/nfs/farm/g/glast/software/fermi/${variant}/${project}"
                        sh "curl -O ${BUILD_URL}/artifact/${variant}/${release_tag}.tar.gz"
                        sh "tar xzf ${release_tag}.tar.gz"
                        sh "cp -pr ${release_tag} ${target_dir}/."
                        // Stage tarball
                        sh "cp ${release_tag}.tar.gz /nfs/farm/g/glast/software/www/pkg/${variant}/${project}/."
                        // Clean up
                        sh "rm ${release_tag}.tar.gz"
                    }
                }
            }
        }
        parallel builders
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
