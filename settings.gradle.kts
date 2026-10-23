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

// Commons comes from the notfossify-commons submodule instead of the published artifact.
// Set e.g. `commonsDir=../notfossify-commons` in ~/.gradle/gradle.properties or pass -PcommonsDir=...
// to build against a different checkout.
includeBuild(providers.gradleProperty("commonsDir").getOrElse("notfossify-commons")) {
    dependencySubstitution {
        substitute(module("org.fossify:commons")).using(project(":commons"))
    }
}
