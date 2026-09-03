/*
 * Copyright © 2025-2026 | Author: @aalsanie
 * Licensed under the Apache License, Version 2.0.
 */
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Shamash"

include("shamash-artifacts")
include("shamash-export")
include("shamash-psi-core")
include("shamash-asm-core")
include("shamash-intellij-plugin")
include("shamash-cli")
