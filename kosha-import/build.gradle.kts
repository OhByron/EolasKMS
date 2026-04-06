plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":kosha-common"))

    // Spring Boot gives us Jackson + HTTP client + CLI runner plumbing
    // for free. We don't need data-jpa, security, or actuator — this
    // is a single-process CLI tool, not a service.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}

// Build a runnable fat jar via bootJar. The Spring Boot plugin default
// output is `build/libs/kosha-import-<version>.jar` which is fine for
// scripts; if we want a stable name we can set `archiveFileName.set(...)`
// in a later pass when the CLI gets its own release cadence.
tasks.bootJar {
    mainClass.set("dev.kosha.import.KoshaImportApplicationKt")
    archiveClassifier.set("")
}

// The regular jar task conflicts with bootJar's fat jar output — disable
// it to avoid confusion about which one to run.
tasks.jar {
    enabled = false
}
