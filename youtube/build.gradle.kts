// The YouTube Store plug-in (Gradle :youtube), in this repo since 2026-11-19 (owner: rebuilt from scratch, public).
// Pure Kotlin/JVM like :storeapi: it implements StoreSource and the app finds it by class name
// (StoreSources.YOUTUBE_CLASS = com.argus.assistant.mediaapps.youtube.YouTubeSource). Required in every build.
// No third-party extractor: its own InnerTube client over HttpURLConnection, JSON via org.json (part of Android;
// compileOnly here, the real jar only for the JVM tests). No GPL code (THIRD_PARTY_NOTICES, "YouTube plug-in").
plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":storeapi"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    compileOnly("org.json:json:20240303")          // Android ships org.json; never bundled from here
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

// The live smoke test (YouTubeLiveSmokeTest) only runs with YT_LIVE=1 (youtube-daily.yml); unit tests never touch the network.
tasks.test {
    val live = System.getenv("YT_LIVE").orEmpty()
    inputs.property("ytLive", live)
    if (live == "1") outputs.upToDateWhen { false }
    testLogging { events("failed", "skipped"); showStandardStreams = live == "1"; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
