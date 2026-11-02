/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.util.glob

import java.util.concurrent.ConcurrentHashMap

/**
 * `*` and `?` stay within a path segment; `**` crosses segments.
 * A double-star directory segment also matches zero directories. Relative globs match at any path depth.
 */
object GlobMatcher {
    private val cache = ConcurrentHashMap<String, Regex>()

    fun matches(
        glob: String,
        path: String,
    ): Boolean {
        val nPath = normalizePath(path)
        val nGlob = normalizePath(glob)

        val rx = cache.computeIfAbsent(nGlob) { compile(it) }
        if (rx.matches(nPath)) return true

        if (!nGlob.startsWith("/")) {
            val anyKey = normalizePath("**/$nGlob")
            val anyRx = cache.computeIfAbsent(anyKey) { compile(it) }
            if (anyRx.matches(nPath)) return true
        }

        return false
    }

    fun normalizePath(path: String): String {
        val p = path.replace('\\', '/')
        val stripped = p.replace(Regex("^[A-Za-z]:/"), "/")
        return stripped.replace(Regex("/+"), "/")
    }

    private fun compile(normalizedGlob: String): Regex {
        val g = normalizePath(normalizedGlob)

        val sb = StringBuilder()
        sb.append('^')

        var i = 0
        while (i < g.length) {
            val c = g[i]

            when (c) {
                '*' -> {
                    val hasNext = i + 1 < g.length
                    val isDouble = hasNext && g[i + 1] == '*'
                    if (isDouble) {
                        val hasSlashAfter = (i + 2 < g.length && g[i + 2] == '/')
                        if (hasSlashAfter) {
                            // "**/" matches zero or more directories.
                            sb.append("(?:.*/)?")
                            i += 3
                            continue
                        } else {
                            sb.append(".*")
                            i += 2
                            continue
                        }
                    } else {
                        sb.append("[^/]*")
                        i += 1
                        continue
                    }
                }

                '?' -> {
                    sb.append("[^/]")
                    i += 1
                    continue
                }

                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                    sb.append('\\').append(c)
                    i += 1
                    continue
                }

                else -> {
                    sb.append(c)
                    i += 1
                    continue
                }
            }
        }

        sb.append('$')
        return Regex(sb.toString())
    }
}
