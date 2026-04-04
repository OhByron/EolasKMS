plugins {
    id("org.springframework.boot")
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":kosha-common"))
    implementation(project(":kosha-identity"))
    implementation(project(":kosha-document"))
    implementation(project(":kosha-workflow"))
    implementation(project(":kosha-storage"))
    implementation(project(":kosha-taxonomy"))
    implementation(project(":kosha-search"))
    implementation(project(":kosha-notification"))
    implementation(project(":kosha-retention"))
    implementation(project(":kosha-audit"))
    implementation(project(":kosha-reporting"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
}
