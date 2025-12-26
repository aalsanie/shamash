package io.shamash.asm.model

/**
 * Deterministic severity levels for Shamash findings.
 *
 * Contract:
 * Lower = more severe.
 */
// - Keep names/labels stable - report export depends on them.
enum class Severity(val rank: Int, val label: String) {
    CRITICAL(0, "Critical"),
    HIGH(1, "High"),
    MEDIUM(2, "Medium"),
    LOW(3, "Low"),
    INFO(4, "Info");

    override fun toString(): String = label
}
