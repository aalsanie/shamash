package io.shamash.asm.model

/**
 * Where a class file came from.
 *
 * ASM-1 (Option B): scan module output directories + project dependency jars.
 */
enum class AsmOrigin {
    MODULE_OUTPUT,
    DEPENDENCY_JAR
}
