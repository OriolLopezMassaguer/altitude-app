pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-milestone") }

    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-milestone") }
    }
}

rootProject.name = "AltitudeApp"
include(":app")