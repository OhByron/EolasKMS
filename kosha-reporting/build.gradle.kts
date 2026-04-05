plugins {
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":kosha-common"))
    implementation(project(":kosha-retention"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
}
