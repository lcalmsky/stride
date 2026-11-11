@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":lang"))
    implementation(project(":search"))
    implementation(project(":plugins:lang-kotlin"))
    implementation(project(":plugins:lang-json"))
    implementation(compose.desktop.currentOs)
    implementation(libs.pty4j)
    implementation(libs.jediterm.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(compose.uiTest)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    val runIntegration = providers.systemProperty("stride.runIntegration").orNull == "true"
    useJUnitPlatform {
        if (!runIntegration) excludeTags("slow")
    }
    systemProperty("java.awt.headless", "true")
    systemProperty("stride.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("stride.runIntegration", runIntegration.toString())
}

compose.desktop {
    application {
        mainClass = "stride.app.MainKt"
        // `:app:run` must use the same JDK 21 as the build, not whatever JVM started Gradle.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile.absolutePath
        providers.gradleProperty("openPath").orNull?.let { args += it }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "strIDE"
            packageVersion = "1.1.0"
            description = "A keyboard-driven code editor"
            vendor = "stride"
            // suggestRuntimeModules output, plus java.logging (java.util.logging in Main)
            // and java.desktop (AWT FileDialog, Compose windowing).
            modules("java.instrument", "jdk.unsupported", "java.logging", "java.desktop")
            macOS {
                bundleID = "com.github.lcalmsky.stride"
                iconFile.set(project.file("src/main/packaging/strIDE.icns"))
            }
        }
    }
}
