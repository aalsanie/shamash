package io.shamash.psi.util

object ShamashMessages {
    const val PREFIX = "Shamash: "

    fun msg(text: String): String = PREFIX + text
}
