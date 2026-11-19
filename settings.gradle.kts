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
// 2026-11-19 (owner): the YouTube plug-in is REQUIRED, there is no build without it. It lives in this repo (youtube/,
// rebuilt in-repo on 2026-11-19; the old private plug-in repo is gone), so a missing folder means a broken checkout.
if (!file("youtube/build.gradle.kts").exists()) throw GradleException(
    "youtube/ is missing: the YouTube plug-in (Gradle :youtube) is part of this repo and required, there is no build without it. " +
    "Restore it from git (git checkout -- youtube)."
)
include(":youtube")
