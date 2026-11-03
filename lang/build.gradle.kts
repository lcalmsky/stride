plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(project(":plugins:lang-kotlin"))
    testRuntimeOnly(project(":plugins:lang-json"))
}

tasks.test {
    useJUnitPlatform()
}
