plugins {
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":kosha-common"))
    implementation(project(":kosha-identity"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // JSON Logic evaluation for conditional workflow steps (Pass 5.3).
    // io.github.jamsesso:json-logic-java is a small, dependency-free
    // evaluator — no SpEL, no scripting engine, safe by construction.
    implementation("io.github.jamsesso:json-logic-java:1.0.7")
}
