// 插件仓库：Gradle 解析“构建脚本里用到的插件”（例如 Android Gradle Plugin）时去哪里找；
// google() 提供 AGP，gradlePluginPortal() 是 Gradle 官方插件仓库；
// 一旦显式配置了 repositories，就必须自己把官方仓库写回来，否则默认仓库不再生效。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 依赖仓库：集中声明，并通过 FAIL_ON_PROJECT_REPOS 禁止各模块自己再写 repositories，
// 避免同一个依赖在不同模块解析到不同来源。
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidJavaShell"
include(":core")
include(":service")
