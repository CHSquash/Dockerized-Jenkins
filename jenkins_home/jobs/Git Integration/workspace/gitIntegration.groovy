import org.yaml.snakeyaml.Yaml
import com.google.gson.Gson
import java.util.Scanner

def gitApiHost = ''
def gitApiToken = ''
def gitLabVersion = ''

def systemEnv = System.getenv()

// TODO: INJECT [JENKINS_ENV_NAME] AS ENV VARIABLE ?

def targetEnvironment = systemEnv['JENKINS_ENV_NAME']

def yamlFilePath = sprintf('/var/jenkins_home/jobs/config/%1$s.yaml', [targetEnvironment])
File newConfiguration = new File(yamlFilePath);
InputStream is = new FileInputStream(newConfiguration);
def yamlMap = new Yaml().load(is)

// def configPath = streamFileFromWorkspace(yamlFilePath)
// def yamlMap = new Yaml().load(configPath)

def jsonString = new Gson().toJson(yamlMap);

def jsonHashMap = new groovy.json.JsonSlurper().parseText(jsonString)
def jsonList = new ArrayList<>(jsonHashMap.values());

def gitRepoHashMap = new HashMap<>()
def projects = new ArrayList<>()
def pageNumber = 1

while (true) {
    def projectsApi = new URL("http://${gitApiHost}/api/v3/projects/all?page=${pageNumber}&per_page=100&private_token=${gitApiToken}")
    def parseProjects = new groovy.json.JsonSlurper().parse(projectsApi.newReader())
    if (parseProjects.size() == 0)
        break
    projects.addAll(parseProjects)
    pageNumber++
}

for (env in jsonList) {
    for (project in projects) {
        if (env.gitProjectTag in project.tag_list) {
            if (env.isTriggeredByUpstream) {
                jobPath = Eval.me("project", project, env.jobPath)
                gitRepoHashMap.put(jobPath, project.ssh_url_to_repo)
            }
        }
    }
}

for (env in jsonList) {
    for (project in projects) {
        projectLabels = project.tag_list
        if (env.gitProjectTag in projectLabels) {
            jobName = project.name
            pathName = project.path
            repoUrl = project.ssh_url_to_repo
            defaultBranch = project.default_branch

            projectDescription = Eval.me("project", project, env.projectDescription[0] )
            folderDescription = Eval.me("project", project, env.folderDescription[0] )
            upstreamUrlPath = Eval.me("project", project, env.upstreamUrlPath)
            webhookUrlPath = Eval.me("project", project, env.webhookUrlPath)
            jobPath = Eval.me("project", project, env.jobPath)
            branchPath = Eval.me("project", project, env.branchListPath)

            String replacedUrlPath = upstreamUrlPath.toString()

            upstreamList = []

            if (env.isTriggeredByUpstream) {
                upstreamList = getUpstreamList(replacedUrlPath, gitRepoHashMap)
            }
            if (env.isTriggeredByWebhook) {
                setWebhook(webhookUrlPath)
            }

            branchToClone = setBranch(branchPath, env.branch, defaultBranch)

            setFolderStructure(jobPath, env.environmentName, folderDescription)
            jenkins_home = systemEnv['JENKINS_HOME']
            customWorkspace = jobPath.toString().replaceAll("/", "/jobs/")
            workspace = sprintf('%1$s%2$s%3$s%4$s%5$s', [jenkins_home, "/jobs/", customWorkspace, "/", project.path])

            job(jobPath) {
                description(projectDescription)
                logRotator {
                    numToKeep(env.buildNumToKeep)
                }
                customWorkspace(workspace)
                properties {
                    sidebarLinks {
                        link(
                                project.web_url,
                                'Git Repository',
                                '')
                    }
                }

                if (env.isSourceCodeRequired) {
                    scm {
                        git {
                            browser {
                                gitLab(gitApiHost, gitLabVersion)
                            }
                            remote {
                                url(repoUrl)
                            }
                            branch(branchToClone)
                            extensions {
                                cleanBeforeCheckout()
                                submoduleOptions {
                                    recursive(env.isSourcePullSubmodules)
                                }
                                cloneOptions {
                                    shallow(true)
                                }
                                wipeOutWorkspace()
                            }
                        }
                    }
                }
                wrappers {
                    buildUserVars()
                }
                publishers {
                    slackNotifications {
                        projectChannel(env.slackChannel)  // projectChannel can be changed
                        notifySuccess()
                        notifyFailure()
                        notifyNotBuilt()
                        notifyUnstable()
                        notifyBackToNormal()
                        notifyRepeatedFailure()
                    }
                }
                triggers {
                    if (env.isTriggeredByWebhook) {
                        scm(env.schedule)
                        gitlabPush {
                            buildOnMergeRequestEvents(false)
                            buildOnPushEvents(true)
                            enableCiSkip(false)
                            setBuildDescription(false)
                            addNoteOnMergeRequest(false)
                            rebuildOpenMergeRequest('never')
                            addVoteOnMergeRequest(false)
                            useCiFeatures(false)
                            acceptMergeRequestOnSuccess(false)
                            allowAllBranches(false)
                            includeBranches(branchToClone)
                        }
                    } else {
                        scm(env.schedule)
                    }
                    for (upstreamName in upstreamList) {
                        upstream(upstreamName, "SUCCESS")
                    }
                }
                steps {
                    for (shellStep in env.shellSteps) {
                        shell(Eval.me("project", project, shellStep))
                    }
                }
            }
        }
    }
}

void setFolderStructure(jobPath, environment, folderdescription) {
    folderName = ""
    tempStructure = jobPath.toString().split("/")
    folderStructure = Arrays.copyOf(tempStructure, tempStructure.length - 1)
    for (folderOrder in folderStructure) {
        folderName = folderName + folderOrder.trim()
        if (folderOrder == environment) {
            folder(folderName) {
                description(folderdescription)
            }
        }
        folder(folderName) {}
        folderName = folderName + "/"
    }
}

void setWebhook(urlLink) {
    webhookApi = new URL(urlLink)
    webhooks = new groovy.json.JsonSlurper().parse(webhookApi.newReader())
    webhooksList = new ArrayList<>()

    systemEnvironment = System.getenv()
    jenkinsHostName = systemEnvironment['JENKINS_FQDN']

    for (webhook in webhooks) {
        webhooksList.add(webhook.url)
    }
    String checkIdempotent = "http://" + jenkinsHostName + "/gitlab/build_now"

    if (!(checkIdempotent in webhooksList)) {
        def targetWebhookUrl = new URL(urlLink)
        def httpConnection = (HttpURLConnection) targetWebhookUrl.openConnection()

        httpConnection.setDoOutput(true)
        httpConnection.setRequestMethod("POST")
        httpConnection.setRequestProperty("Content-Type", "application/json")

        def input = "{\"url\":\"${checkIdempotent}\"}"

        // Send post request
        def outputStream = httpConnection.getOutputStream()
        outputStream.write(input.getBytes())
        outputStream.flush()
        outputStream.close()
        def responseBuffer = new BufferedReader(new InputStreamReader((httpConnection.getInputStream())))

        def output
        println("Output from Server:\n")
        while ((output = responseBuffer.readLine()) != null) {
            println(output)
        }
        responseBuffer.close()
        httpConnection.disconnect()
    }
}

def setBranch(urlPath, configBranch, defaultBranch) {
    branchListApi = new URL(urlPath)
    branchListJsonFormat = new groovy.json.JsonSlurper().parse(branchListApi.newReader())
    branchList = new ArrayList<>()

    for (branch in branchListJsonFormat) {
        branchList.add(branch.name)
    }

    if (configBranch in branchList)
        return configBranch
    else
        return defaultBranch
}

def getKeyFromValue(map, value) {
    getJobPathlist = []
    for (item in map.keySet()) {
        if (map.get(item).equals(value)) {
            getJobPathlist.add(item)
        }
    }
    return getJobPathlist;
}

def getUpstreamList(urlLink, Map map) {
    upstreamApi = new URL(urlLink)
    upstreamStringFormat = "null"
    upstreamInArray = new ArrayList<String>();
    try {
        upstreamEncodedBase64 = new groovy.json.JsonSlurper().parse(upstreamApi.newReader())
        if (upstreamEncodedBase64.content) {
            upstreamDecoded = Base64.getDecoder().decode(upstreamEncodedBase64.content.replace("\n", ""))
            upstreamStringFormat = new String(upstreamDecoded)
            upstreamInArray = upstreamStringFormat.split("\n")
        }
    }
    catch (Exception e) {
    }
    upstreamProjectName = new ArrayList<String>()
    for (upstream in upstreamInArray) {
        println "upstream :" + upstream
        getJobPathlist = getKeyFromValue(map, upstream.trim())
        for (jobpath in getJobPathlist) {
            upstreamProjectName.add(jobpath)
        }
    }
    return upstreamProjectName
}
