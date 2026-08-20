plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    // @ConfigurationProperties 注解编译期可见；运行时由消费者服务自带 Boot。
    compileOnly("org.springframework.boot:spring-boot")

    testImplementation(libs.spring.boot.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
