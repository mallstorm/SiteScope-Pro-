pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // Tesseract4Android публикуется только на JitPack; подключаем строго одну группу.
        maven { url = uri("https://jitpack.io"); content { includeGroup("cz.adaptech.tesseract4android") } }
    }
}
rootProject.name = "SiteScopePro"
include(":app")
