pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

rootProject.name = "grassland-platform"

include(
    "platform-crypto",
    "platform-storage",
    "platform-identity-assertion",
    "platform-http",
    "platform-reporting",
    "platform-messaging",
    "platform-database",
    "services:database-bootstrap",
    "services:edge-bff",
    "services:identity-service",
    "services:marketplace-service",
    "services:finance-service",
    "services:trust-service",
    "services:intelligence-service",
)
