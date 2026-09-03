import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.concurrent.Callable

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.intellij.platform") version "2.10.5" apply false
    id("com.diffplug.spotless") version "8.9.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9" apply false
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

allprojects {
    group = "io.github.aalsanie"
    version = "0.92.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(17)
        }
        dependencies.apply {
            add("testImplementation", kotlin("test"))
            add("testImplementation", "junit:junit:4.13.2")
        }
        tasks.withType(Test::class.java).configureEach {
            maxHeapSize = "2g"
            useJUnitPlatform()
        }
    }

    apply(plugin = "com.diffplug.spotless")
    plugins.withId("com.diffplug.spotless") {
        extensions.configure<SpotlessExtension> {
            kotlin {
                ktlint()
                licenseHeaderFile(rootProject.file("spotless/HEADER.kt"), "package ")
            }
            kotlinGradle { ktlint() }
        }
    }

    apply(plugin = "org.jetbrains.kotlinx.kover")

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            pom {
                name.set(project.name)
                description.set(provider { project.description })
                url.set("https://github.com/aalsanie/shamash")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("aalsanie")
                        name.set("Ahmad Al-sanie")
                        url.set("https://github.com/aalsanie")
                    }
                }
                scm {
                    url.set("https://github.com/aalsanie/shamash")
                    connection.set("scm:git:https://github.com/aalsanie/shamash.git")
                    developerConnection.set("scm:git:ssh://git@github.com/aalsanie/shamash.git")
                    tag.set(provider { "v${project.version}" })
                }
            }
        }
        extensions.configure<SigningExtension> {
            setRequired(Callable { gradle.taskGraph.allTasks.any { it.name.endsWith("ToMavenCentralRepository") } })
        }
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "Test"
                url = rootProject.layout.buildDirectory.dir("test-maven-repository").get().asFile.toURI()
            }
        }
        tasks.withType<Jar>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            manifest.attributes("Implementation-Version" to project.version, "Implementation-Title" to project.name)
            from(rootProject.file("LICENSE")) { into("META-INF") }
        }
    }
}

tasks.register("publishLibrariesToTestRepository") {
    group = "publishing"
    description = "Publishes the three libraries to build/test-maven-repository for consumer verification."
    dependsOn(
        ":shamash-artifacts:publishAllPublicationsToTestRepository",
        ":shamash-export:publishAllPublicationsToTestRepository",
        ":shamash-asm-core:publishAllPublicationsToTestRepository",
    )
}
