pluginManagement {
    repositories {
        maven { setUrl("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        maven { setUrl("https://mirrors.tencent.com/nexus/repository/gradle-plugins/") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        maven { setUrl("https://jitpack.io") }
        google()
        mavenCentral()
    }
}

rootProject.name = "ChargeLog"
include(":app")
 