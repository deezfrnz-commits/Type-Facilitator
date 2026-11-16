// TF_CORE_ONLY=1 builds without the Android SDK: the typing engine (:core) and its tests, plus
// :localcheck, which compiles the app sources against the framework classes on Maven Central.
val coreOnly = System.getenv("TF_CORE_ONLY") != null
pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.20"
        id("org.jetbrains.kotlin.android") version "2.2.20"
        id("com.android.application") version "8.11.1"
    }
    repositories {
        if (System.getenv("TF_CORE_ONLY") == null) google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        if (!coreOnly) google()
        mavenCentral()
    }
}
rootProject.name = "TypeFacilitator"
include(":core")
if (coreOnly) include(":localcheck") else include(":app")
