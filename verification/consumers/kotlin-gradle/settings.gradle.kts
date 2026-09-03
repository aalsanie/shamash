pluginManagement {
    repositories { gradlePluginPortal() }
    plugins {
        kotlin("jvm") version providers.gradleProperty("consumerKotlinVersion").get()
    }
}

rootProject.name = "shamash-kotlin-consumer"
