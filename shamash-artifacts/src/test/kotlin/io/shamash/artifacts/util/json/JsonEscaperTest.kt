/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.util.json

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonEscaperTest {
    @Test
    fun escape_escapesBackslashAndQuoteAndControlCharacters() {
        val inStr = "\\ \" \b \u000C \n \r \t"
        val out = JsonEscaper.escape(inStr)

        assertEquals("\\\\ \\\" \\b \\f \\n \\r \\t", out)
    }

    @Test
    fun escape_escapesAsciiControlCharsToUnicodeEscape() {
        val inStr = "a\u0001b"
        val out = JsonEscaper.escape(inStr)
        assertEquals("a\\u0001b", out)
    }

    @Test
    fun escape_doesNotOverEscapeNormalUnicode() {
        val inStr = "pi=π"
        val out = JsonEscaper.escape(inStr)
        assertEquals("pi=π", out)
    }
}
