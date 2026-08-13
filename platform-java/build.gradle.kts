import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

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
        jvmArgs(
            "-javaagent:${mockitoAgent.asPath}",
            "-Xshare:off",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
        )
    }
}
