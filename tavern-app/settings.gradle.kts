pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The ONNX runtime is vendored (see local-repo/README.md): this build runs offline, and
        // resolving a 24 MB artifact from the network on every clean checkout is not worth it.
        maven { url = uri("local-repo") }
    }
}

rootProject.name = "TavernApp"
include(":app")
