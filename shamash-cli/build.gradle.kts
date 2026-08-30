/*
 * Shamash is a JVM architecture enforcement tool.
 * Copyright © 2025-2026 | Author: @aalsanie
 * Licensed under the Apache License, Version 2.0.
 */
plugins {
    application
}

dependencies {
    implementation(project(":shamash-asm-core"))
    implementation(project(":shamash-export"))
    implementation(project(":shamash-artifacts"))
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

application {
    mainClass.set("io.shamash.cli.MainKt")
    applicationName = "shamash"
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "shamash-cli",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
