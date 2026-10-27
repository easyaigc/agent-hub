pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AgentHubMobile"
include(":android", ":mobile-core")
project(":android").projectDir = file("apps/android")
project(":mobile-core").projectDir = file("packages/mobile-core")

// The independent cold-process observer is only available in acceptance builds.
if (providers.gradleProperty("agentHubAcceptance").orNull == "true") {
    include(":android-inbox-examiner")
    project(":android-inbox-examiner").projectDir = file("apps/android-inbox-examiner")
}
