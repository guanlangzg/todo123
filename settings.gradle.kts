// Gradle root settings. Fixed path, see docs/设计/工程布局与版本锁定.md §1.
pluginManagement {
    repositories {
        // Repositories are declared explicitly (not left to an external init script) so the
        // build is reproducible on this machine, per docs/设计/架构契约.md E8.
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "arttodo"

include(":app")
