package io.shamash.asm.model

/**
 * Lightweight method metadata.
 *
 * We keep descriptors for later analysis (e.g., dependency graph, overload counts).
 */
data class AsmMethodInfo(
    val name: String,
    val descriptor: String,
    val access: Int
)
