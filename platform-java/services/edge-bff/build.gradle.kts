plugins {
    java
    alias(libs.plugins.spring.boot)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.cloud.dependencies))
    implementation(libs.spring.cloud.gateway.webflux)
    implementation(libs.spring.boot.actuator)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.reactor.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "edge-bff.jar"
}
