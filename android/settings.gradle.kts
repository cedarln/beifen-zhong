// 插件与依赖只能从这里解析。这台机器上 repo1.maven.org 不可达，
// 所以版本一律钉在 ~/.gradle 缓存里已有的那几个（见 libs.versions.toml 的注释）。
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
    }
}

rootProject.name = "backing-up"
include(":app")
