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
    // Micrometer → Prometheus registry. Ships /actuator/prometheus so feature
    // work from Pass 4 onwards can register counters/timers at authoring time
    // rather than retrofitting for Pass 6 dashboards. Bootstrapped per the
    // "instrument as we go" policy locked in the roadmap.
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.nats:jnats:2.25.2")
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
}
