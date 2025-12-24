package io.shamash.psi.util

/**
 * Naming conventions enforced by Shamash.
 */

object NamingRules {

    private val bannedSuffixes = listOf(
        "Manager",
        "Helper",
        "Impl"
    )

    fun hasBannedSuffix(className: String): Boolean =
        bannedSuffixes.any { className.endsWith(it) }

    fun isUtilityName(className: String): Boolean =
        className.endsWith("Util") ||
                className.endsWith("Utils") ||
                className.endsWith("Workflow")

    fun isAbbreviated(name: String): Boolean =
        name.length <= 3 && name.all { it.isUpperCase() }
}
