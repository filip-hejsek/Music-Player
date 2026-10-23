pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}
include(":app")

// Build against a local checkout of the commons fork instead of the published artifact.
// Set e.g. `commonsDir=../notfossify-commons` in ~/.gradle/gradle.properties or pass -PcommonsDir=...
providers.gradleProperty("commonsDir").orNull?.let { dir ->
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module("org.fossify:commons")).using(project(":commons"))
        }
    }
}
