plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api("org.apache.poi:poi-ooxml:5.4.1")
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
