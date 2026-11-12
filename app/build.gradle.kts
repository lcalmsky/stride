@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import java.util.concurrent.TimeUnit
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

// The native glass bridge is built with clang, not a Gradle dependency: JNI into AppKit
// needs Objective-C++ and the build JDK's jawt headers.
val nativeJdkHome = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}.get().metadata.installationPath.asFile
val nativeGlassSource = rootProject.layout.projectDirectory.file("native/glass/stride_glass.mm")
val nativeGlassDir = layout.buildDirectory.dir("native/glass")
val nativeGlassLibrary = nativeGlassDir.map { it.file("libstrideglass.dylib") }
val isMacOs = System.getProperty("os.name").lowercase().contains("mac")
val nativeGlassEnabled = providers.gradleProperty("stride.nativeGlass").orNull?.toBoolean() ?: true

// Probed once at configuration: only the Command Line Tools provide a working clang++.
fun clangAvailable(): Boolean = runCatching {
    val process = ProcessBuilder("clang++", "--version").redirectErrorStream(true).start()
    val finished = process.waitFor(5, TimeUnit.SECONDS)
    if (!finished) process.destroyForcibly()
    process.inputStream.close()
    finished && process.exitValue() == 0
}.getOrDefault(false)

val hasClang = clangAvailable()
var nativeSkipLogged = false

val buildNativeGlass = tasks.register<Exec>("buildNativeGlass") {
    description = "Builds the native macOS glass JNI library."
    onlyIf {
        val enabled = isMacOs && nativeGlassEnabled && hasClang
        if (!enabled && !nativeSkipLogged) {
            nativeSkipLogged = true
            logger.lifecycle(
                when {
                    !isMacOs -> "native glass: skipped (not macOS)"
                    !nativeGlassEnabled -> "native glass: skipped (-Pstride.nativeGlass=false)"
                    else -> "native glass: skipped (clang++ not available); the app uses the Skia glass path"
                },
            )
        }
        enabled
    }
    inputs.file(nativeGlassSource)
    outputs.file(nativeGlassLibrary)
    doFirst { nativeGlassDir.get().asFile.mkdirs() }
    commandLine(
        "clang++",
        "-dynamiclib",
        "-O2",
        "-fobjc-arc",
        "-std=c++17",
        "-mmacosx-version-min=11.0",
        "-I${nativeJdkHome}/include",
        "-I${nativeJdkHome}/include/darwin",
        "-framework", "AppKit",
        "-framework", "QuartzCore",
        "-framework", "Foundation",
        "-o", nativeGlassLibrary.get().asFile.absolutePath,
        nativeGlassSource.asFile.absolutePath,
    )
}

val appResourcesDir = layout.buildDirectory.dir("native-resources")
val prepareNativeGlassResources = tasks.register<Copy>("prepareNativeGlassResources") {
    description = "Copies the native glass library into the app resources directory."
    onlyIf { isMacOs && nativeGlassEnabled && hasClang }
    dependsOn(buildNativeGlass)
    // appResourcesRootDir merges `<root>/common` and `<root>/<os>` into the app resources.
    from(nativeGlassDir)
    into(appResourcesDir.map { it.dir("common") })
}

tasks.matching {
    it.name == "run" || it.name == "prepareAppResources" || it.name.startsWith("createDistributable") ||
        it.name.startsWith("packageDistributionForCurrentOS") || it.name.startsWith("packageDmg")
}.configureEach {
    dependsOn(prepareNativeGlassResources)
}

// `:app:run` loads the library from the build directory; the packaged app uses
// `compose.application.resources.dir` instead, so only the run task gets this path.
tasks.matching { it.name == "run" }.configureEach {
    (this as org.gradle.api.tasks.JavaExec).jvmArgs(
        "-Dstride.glass.library=${nativeGlassLibrary.get().asFile.absolutePath}",
    )
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
    dependsOn(buildNativeGlass)
    useJUnitPlatform {
        if (!runIntegration) excludeTags("slow")
    }
    // The native glass smoke test needs a real AWT peer; it creates no visible window.
    systemProperty("java.awt.headless", "false")
    systemProperty("stride.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("stride.runIntegration", runIntegration.toString())
    // Disabled builds (or ones without clang) must not pick up a stale library from an earlier
    // build, so the tests skip the native paths with a reason.
    val nativeLibrary = if (nativeGlassEnabled && hasClang) {
        nativeGlassLibrary.get().asFile.absolutePath
    } else {
        "${nativeGlassLibrary.get().asFile.absolutePath}.disabled"
    }
    systemProperty("stride.glass.library", nativeLibrary)
    // The appearance-order test forks a fresh JVM; it must see the same test runtime classes.
    systemProperty("stride.test.classpath", sourceSets["test"].runtimeClasspath.asPath)
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
            packageVersion = "1.3.0"
            description = "A keyboard-driven code editor"
            vendor = "stride"
            // The native glass library rides in Contents/Resources; Compose exposes the
            // directory as the `compose.application.resources.dir` system property.
            appResourcesRootDir.set(appResourcesDir)
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
