import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless) apply false
}

allprojects {
    group = "com.grassland"
    version = "0.1.0-SNAPSHOT"

    val mockitoAgent = configurations.create("mockitoAgent") {
        isTransitive = false
    }
    dependencies.add(mockitoAgent.name, rootProject.libs.mockito.core)

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty") {
                useVersion("4.2.16.Final")
                because("CVE fixes required by the image vulnerability gate")
            }
            if (requested.group == "com.ongres.scram") {
                useVersion("3.3")
                because("CVE-2026-53712")
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        // CI 控制台默认截断异常堆栈（只留 3 帧），跨轮 flake 根因排查需要完整帧。
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        inputs.files(mockitoAgent)
        // IT 套件规模（intelligence 800+ 项、多 Spring 上下文缓存 + Testcontainers）已超 Gradle 默认 512m，
        // 全量运行会出现 context 解析 OOM；统一放宽到 2g。
        maxHeapSize = "2g"
        jvmArgs(
            "-javaagent:${mockitoAgent.asPath}",
            "-Xshare:off",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
        )
    }

    // Java 覆盖率度量（2026-08-20）：先度量后设门——统一 XML+HTML 报告，test 完成即生成；
    // 阈值门禁待基线数字稳定后另批引入。0.8.15 支持 Java 25 class 文件。
    apply(plugin = "jacoco")
    configure<JacocoPluginExtension> {
        toolVersion = "0.8.15"
    }
    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
    tasks.matching { it.name == "test" }.configureEach {
        finalizedBy(tasks.matching { it.name == "jacocoTestReport" })
    }

    // 任务书 #67:覆盖率阈值门禁(先度量后设门)。阈值=基线指令覆盖率下取整-5个百分点,
    // 下限 30%;升阈值需另批。未登记的模块不设门(新模块入库时留待下一轮基线)。
    val jacocoInstructionFloor = mapOf(
        "database-bootstrap" to 0.75,      // 基线 80% → 75%
        "edge-bff" to 0.78,                // 基线 83% → 78%
        "finance-service" to 0.83,         // 基线 88% → 83%
        "identity-service" to 0.77,        // 基线 82% → 77%
        "intelligence-service" to 0.77,    // 基线 82% → 77%
        "marketplace-service" to 0.80,     // 基线 85% → 80%
        "platform-crypto" to 0.66,         // 基线 71% → 66%
        "platform-database" to 0.64,       // 基线 69% → 64%
        "platform-financial" to 0.91,      // 基线 96% → 91%
        "platform-http" to 0.80,           // 基线 85% → 80%
        "platform-identity-assertion" to 0.78,  // 基线 83% → 78%
        "platform-messaging" to 0.47,      // 基线 52% → 47%
        "platform-reporting" to 0.79,      // 基线 84% → 79%
        "platform-storage" to 0.85,        // 基线 90% → 85%
        "trust-service" to 0.76,           // 基线 81% → 76%
        // release-migrator 基线 26% 不登记(低于 30% 下限,待改善后另批)
    )
    tasks.withType<JacocoCoverageVerification>().configureEach {
        val floor = jacocoInstructionFloor[project.name]
        if (floor != null) {
            violationRules {
                rule {
                    limit {
                        counter = "INSTRUCTION"
                        value = "COVEREDRATIO"
                        minimum = floor.toBigDecimal()
                    }
                }
            }
        }
    }
    plugins.withId("jacoco") {
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(tasks.matching { it.name == "jacocoTestCoverageVerification" })
        }
    }

    // Java 格式化门禁（2026-08-20）：ratchetFrom 只格式化相对基线变更的文件，
    // 存量 1400+ 文件零 churn。eclipse JDT 格式器（不依赖 javac 内部 API，
    // JDK 25 免疫——palantir 格式器在 25 上 NoSuchMethodError 崩溃，等其适配后可换）。
    apply(plugin = "com.diffplug.spotless")
    plugins.withId("java") {
        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            ratchetFrom("origin/main")
            java {
                eclipse()
            }
        }
    }
}
