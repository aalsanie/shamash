plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    // Intentionally pulls external jars to trigger originForbiddenJarDependencies / allowOnlyRoot
    // (if you scan external buckets)
    implementation("com.google.guava:guava:33.2.1-jre")
    implementation("org.apache.commons:commons-lang3:3.14.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
