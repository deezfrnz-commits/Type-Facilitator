// Development aid, not part of the app: with TF_CORE_ONLY=1 this compiles the Android sources on a
// machine that has no Android SDK (against the framework classes published on Maven Central) and
// runs the tests of the parts that need no device.
plugins {
    id("org.jetbrains.kotlin.jvm")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
sourceSets["main"].kotlin.srcDir("../app/src/main/java")
dependencies {
    implementation(project(":core"))
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
