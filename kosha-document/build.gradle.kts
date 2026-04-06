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
    implementation("io.micrometer:micrometer-core")
}
