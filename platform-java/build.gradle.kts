import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    alias(libs.plugins.spring.boot) apply false
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
}
