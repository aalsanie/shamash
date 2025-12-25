package io.shamash.asm.scan

import io.shamash.asm.model.AsmOrigin

/**
 * A source of class files.
 *
 * - Directory: module output (e.g., build/classes/java/main)
 * - Jar: dependency jar
 */
sealed class ClassFileSource(
    val origin: AsmOrigin,
    val displayName: String,
    val path: String
) {
    class Directory(
        displayName: String,
        path: String
    ) : ClassFileSource(AsmOrigin.MODULE_OUTPUT, displayName, path)

    class Jar(
        displayName: String,
        path: String
    ) : ClassFileSource(AsmOrigin.DEPENDENCY_JAR, displayName, path)
}
