import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.concurrent.Callable

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.intellij.platform") version "2.10.5" apply false
    id("com.diffplug.spotless") version "8.9.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

// Merge production coverage with tests from all modules, including cross-module integration tests.
dependencies {
    kover(project(":shamash-artifacts"))
    kover(project(":shamash-export"))
    kover(project(":shamash-asm-core"))
    kover(project(":shamash-psi-core"))
    kover(project(":shamash-cli"))
    kover(project(":shamash-intellij-plugin"))
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

    val licenseFile = rootProject.file(if (project.name == "shamash-intellij-plugin") "shamash-intellij-plugin/LICENSE" else "LICENSE")
    val sourcePointer = layout.buildDirectory.file("generated/license/SOURCE.txt")
    val writeSourcePointer = tasks.register("writeSourcePointer") {
        outputs.file(sourcePointer)
        doLast {
            val file = sourcePointer.get().asFile
            file.parentFile.mkdirs()
            val revision =
                runCatching {
                    ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
                        .directory(rootProject.projectDir)
                        .redirectErrorStream(true)
                        .start()
                        .let { process ->
                            val result = process.inputStream.bufferedReader().use { it.readText().trim() }
                            result.takeIf { process.waitFor() == 0 && it.matches(Regex("[0-9a-f]{40}")) }
                        }
                }.getOrNull()
            file.writeText("https://github.com/aalsanie/shamash/tree/${revision ?: "v${project.version}"}\n")
        }
    }
    tasks.withType<org.gradle.api.tasks.bundling.Zip>()
        .matching { it is Jar || it.name == "dokkaJavadocJar" }
        .configureEach {
            dependsOn(writeSourcePointer)
            from(licenseFile) {
                into("META-INF")
                rename { "LICENSE" }
            }
            from(rootProject.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
            from(rootProject.file("LICENSES")) { into("META-INF/LICENSES") }
            from(sourcePointer) {
                into("META-INF")
                rename { "SOURCE" }
            }
            if (project.name == "shamash-intellij-plugin") {
                from(rootProject.file("LICENSE")) {
                    into("META-INF/LICENSES")
                    rename { "AGPL-3.0-only.txt" }
                }
            }
        }

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
                licenseHeaderFile(rootProject.file(if (project.name == "shamash-intellij-plugin") "spotless/HEADER-apache.kt" else "spotless/HEADER.kt"), "package ")
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
                        name.set("GNU Affero General Public License, Version 3")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.html")
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
            val requireSigning = providers.gradleProperty("shamashRequireSigning").map { it.toBooleanStrict() }.orElse(false)
            setRequired(
                Callable {
                    requireSigning.get() || gradle.taskGraph.allTasks.any { it.name.endsWith("ToMavenCentralRepository") }
                },
            )
        }
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "Test"
                url =
                    rootProject.providers
                        .gradleProperty("shamashTestRepository")
                        .map { rootProject.file(it) }
                        .orElse(rootProject.layout.buildDirectory.dir("test-maven-repository").map { it.asFile })
                        .get()
                        .toURI()
            }
        }
        tasks.withType<Jar>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            manifest.attributes("Implementation-Version" to project.version, "Implementation-Title" to project.name)
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

// BEGIN LIBRARY ABI: reused when generating the first reviewed API snapshot.
if (providers.gradleProperty("shamashAbiReferenceRoot").isPresent) {
    subprojects {
        if (name in setOf("shamash-artifacts", "shamash-export", "shamash-asm-core")) {
            val moduleName = name
            plugins.withId("org.jetbrains.kotlin.jvm") {
                extensions.configure<KotlinJvmProjectExtension> {
                    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
                    abiValidation {
                        binariesSource.set(org.jetbrains.kotlin.gradle.dsl.abi.BinariesSource.MAVEN_PUBLICATIONS)
                        referenceDumpDir.set(
                            rootProject.file(providers.gradleProperty("shamashAbiReferenceRoot").get()).resolve(moduleName),
                        )
                    }
                }
            }
        }
    }
}
// END LIBRARY ABI
