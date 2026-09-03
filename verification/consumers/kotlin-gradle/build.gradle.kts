plugins {
    kotlin("jvm")
    application
}

repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "ShamashCandidate"
                url = uri(providers.gradleProperty("shamashRepository").get())
            }
        }
        filter { includeGroup("io.github.aalsanie") }
    }
    mavenCentral()
}

dependencies {
    implementation("io.github.aalsanie:shamash-asm-core:${providers.gradleProperty("shamashVersion").get()}")
}

kotlin { jvmToolchain(17) }

sourceSets.main {
    java.srcDir("../maven-java/src/main/java")
    resources.srcDir("../maven-java/src/main/resources")
}

application { mainClass.set("shamash.verification.KotlinSmokeKt") }

tasks.named<JavaExec>("run") {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(
        providers.gradleProperty("shamashVersion").get(),
        providers.gradleProperty("shamashRepositoryPath").get(),
        layout.buildDirectory.dir("smoke project").get().asFile.absolutePath,
    )
}
