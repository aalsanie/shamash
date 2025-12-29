/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.shamash.psi.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight glob matcher for IDE + CLI usage.
 *
 * Supported:
 * - ** matches any characters including '/'
 * - *  matches any characters except '/'
 * - ?  matches a single character except '/'
 */
object GlobMatcher {
    private val cache = ConcurrentHashMap<String, Regex>()

    fun matches(
        glob: String,
        path: String,
    ): Boolean {
        val nPath = normalizePath(path)
        val rx = cache.computeIfAbsent(glob) { compile(it) }
        if (rx.matches(nPath)) return true

        // If the glob is "relative-ish" (does not start with '/'), allow it to match anywhere in the path.
        val g = normalizePath(glob)
        if (!g.startsWith("/")) {
            val anyRx = cache.computeIfAbsent("**/$g") { compile(it) }
            if (anyRx.matches(nPath)) return true
        }
        return false
    }

    fun normalizePath(path: String): String {
        // Normalize to forward slashes, drop redundant leading "file:" etc.
        var p = path.replace('\\', '/')
        if (p.startsWith("file:")) p = p.removePrefix("file:")
        // collapse '//' (but keep leading '//' for UNC is irrelevant for IDE)
        while (p.contains("//")) p = p.replace("//", "/")
        return p.trim()
    }

    private fun compile(glob: String): Regex {
        val g = normalizePath(glob)
        val sb = StringBuilder()
        sb.append('^')

        var i = 0
        while (i < g.length) {
            val c = g[i]
            when (c) {
                '*' -> {
                    val isDouble = (i + 1 < g.length && g[i + 1] == '*')
                    if (isDouble) {
                        sb.append(".*")
                        i++
                    } else {
                        sb.append("[^/]*")
                    }
                }
                '?' -> sb.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                    sb.append('\\').append(c)
                }
                else -> sb.append(c)
            }
            i++
        }

        sb.append('$')
        return Regex(sb.toString())
    }
}
