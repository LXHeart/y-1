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
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(project(":platform-database"))
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.12")

    testImplementation(libs.spring.boot.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

// 独立 release migration job 的载荷：五服务迁移随本镜像一起打包（classpath:db/migratedb/<svc>，
// 与各服务运行时 classpath:db/migration 同源同字节——发布清单校验的对象也是这些文件）。
// srcDir(X) 把 X 的「内容」摊到 resources 根——目的地造到 generated-migrations，内层再进
// db/migratedb/<svc>，最终 classpath 位置 = db/migratedb/<svc>。
val migrationsPayloadRoot = layout.buildDirectory.dir("generated-migrations")

val copyServiceMigrations = tasks.register<Copy>("copyServiceMigrations") {
    into(migrationsPayloadRoot.map { it.dir("db/migratedb") })
    from(layout.projectDirectory.dir("../database-bootstrap/src/main/resources/db/bootstrap")) {
        into("database-bootstrap")
    }
    for (service in listOf("identity-service", "marketplace-service", "finance-service", "trust-service", "intelligence-service")) {
        from(layout.projectDirectory.dir("../" + service + "/src/main/resources/db/migration")) {
            into(service)
        }
    }
}

sourceSets {
    main {
        resources {
            srcDir(migrationsPayloadRoot)
        }
    }
}

tasks.named("processResources") {
    dependsOn(copyServiceMigrations)
}

tasks.test {
    useJUnitPlatform()
    // 迁移测试要起 Docker Postgres（与 database-bootstrap 同款）
    jvmArgs("-Xmx2g")
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "release-migrator.jar"
}
