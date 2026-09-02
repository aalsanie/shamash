plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    // External jars intentionally exercise origin rules when external buckets are scanned.
    implementation("com.google.guava:guava:33.2.1-jre")
    implementation("org.apache.commons:commons-lang3:3.14.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
