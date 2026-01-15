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
    implementation(project(":shamash-export"))
    implementation(project(":shamash-artifacts"))
    implementation(project(":shamash-asm-core"))

    intellijPlatform {
        // used for compilation / runIde / tests
        intellijIdea("2024.2")

        // plugin uses Java + Kotlin IDE APIs
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "2g"
    // useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id = "io.shamash"
        name = "Shamash"
        version = rootProject.version.toString()

        ideaVersion {
            sinceBuild = "242"
            untilBuild = "253.*"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2024.2")
            create(IntelliJPlatformType.IntellijIdea, "2024.3")
            create(IntelliJPlatformType.IntellijIdea, "2025.1")
            create(IntelliJPlatformType.IntellijIdea, "2025.2")
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
        }
    }
}
