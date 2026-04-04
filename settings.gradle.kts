rootProject.name = "kosha"

include(
    "kosha-common",
    "kosha-identity",
    "kosha-document",
    "kosha-workflow",
    "kosha-storage",
    "kosha-taxonomy",
    "kosha-search",
    "kosha-notification",
    "kosha-retention",
    "kosha-audit",
    "kosha-reporting",
    "kosha-app",
)

pluginManagement {
    val kotlinVersion: String by settings
    val springBootVersion: String by settings
    val springDependencyManagementVersion: String by settings

    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.spring") version kotlinVersion
        kotlin("plugin.jpa") version kotlinVersion
        id("org.springframework.boot") version springBootVersion
        id("io.spring.dependency-management") version springDependencyManagementVersion
    }
}
