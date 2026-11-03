rootProject.name = "stride"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

include(":core", ":lang", ":search", ":app", ":plugins:lang-kotlin", ":plugins:lang-json")
