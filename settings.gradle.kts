@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.android") version "2.2.21"
    }
}

rootProject.name = "OnlinePaymentsExample"
include(":compose")
include(":common")
include(":xml")
