// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":shamash-psi-core"))
    implementation(project(":shamash-artifacts"))

    intellijPlatform {
        intellijIdeaCommunity("2024.2.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
}

// Real IDE journeys use Starter/Driver (JUnit 5), isolated from the legacy JUnit 4 fixtures.
val integrationTestSourceSet =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

dependencies {
    intellijPlatform {
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
    }
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
}

val pluginArchive = tasks.named<Zip>("buildPlugin")
val journeySuite = providers.gradleProperty("shamashIdeJourneySuite").orElse("full").get()
require(journeySuite in setOf("full", "essential")) {
    "shamashIdeJourneySuite must be 'full' or 'essential'"
}

val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        dependsOn(pluginArchive)
        systemProperty("path.to.build.plugin", pluginArchive.get().archiveFile.get().asFile.absolutePath)
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform {
            if (journeySuite == "essential") includeTags("essential")
        }
        maxHeapSize = "2g"
        systemProperty("shamash.fixture.root", layout.projectDirectory.dir("src/integrationTest/fixtures").asFile.absolutePath)
        systemProperty("shamash.ide.version", providers.gradleProperty("shamashIdeJourneyVersion").orElse("2024.2.1").get())
        maxParallelForks = 1
    }
}


tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "shamash-intellij-plugin",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.shamash"
        name = "Shamash"
        version = rootProject.version.toString()

        ideaVersion {
            sinceBuild = "242.21829.142"
            untilBuild = "262.*"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2024.2.1")
            create(IntelliJPlatformType.IntellijIdea, "2025.2")
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("JETBRAINS_CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("JETBRAINS_PRIVATE_KEY")
        password = providers.environmentVariable("JETBRAINS_PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
        channels = listOf("default")
    }
}
