/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.util.json

import kotlin.text.iterator

object JsonEscaper {
    fun escape(s: String): String {
        val out = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '\\' -> {
                    out.append("\\\\")
                }

                '"' -> {
                    out.append("\\\"")
                }

                '\b' -> {
                    out.append("\\b")
                }

                '\u000C' -> {
                    out.append("\\f")
                }

                '\n' -> {
                    out.append("\\n")
                }

                '\r' -> {
                    out.append("\\r")
                }

                '\t' -> {
                    out.append("\\t")
                }

                else -> {
                    if (ch < ' ') {
                        out.append("\\u")
                        out.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }
}
