pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Maven Central rate limits Robolectric artifacts (HTTP 429) on CI.
        // Keep the complete group on Google's verified Maven Central mirror,
        // not just the SDK jars loaded by Robolectric at runtime.
        exclusiveContent {
            forRepository {
                maven {
                    name = "robolectricGoogleMirror"
                    url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                }
            }
            filter { includeGroup("org.robolectric") }
        }
        google(); mavenCentral()
        // Tesseract4Android публикуется только на JitPack; подключаем строго одну группу.
        maven { url = uri("https://jitpack.io"); content { includeGroup("cz.adaptech.tesseract4android") } }
    }
}
rootProject.name = "SiteScopePro"
include(":app")
