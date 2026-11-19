pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // v0.29.2: Termux terminal-emulator + terminal-view (Apache-2.0) for Argus Terminal.
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app") } }
    }
}
rootProject.name = "Argus"
include(":app")
// v0.30.1 Argus Music / Video Stores: the source interface, and the YouTube plug-in.
include(":storeapi")
// 2026-11-19 (owner): the YouTube plug-in is REQUIRED, there is no build without it. It lives in a private repo and is
// checked out into youtube/ (CI: right after the checkout, tools/rename/youtube-plugin.sh renames its package).
if (!file("youtube/build.gradle.kts").exists()) throw GradleException(
    "youtube/ is missing: the YouTube plug-in (Gradle :youtube, private repo) is required, there is no build without it. " +
    "Clone the plug-in repo into youtube/ and run tools/rename/youtube-plugin.sh (README, \"YouTube plug-in\")."
)
include(":youtube")
