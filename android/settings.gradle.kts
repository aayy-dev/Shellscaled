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
        // libsu is only published on JitPack; nothing else may resolve from it.
        maven("https://jitpack.io") { content { includeGroup("com.github.topjohnwu.libsu") } }
    }
}

rootProject.name = "Shellscaled"
include(":app")
