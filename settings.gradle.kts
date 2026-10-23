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
