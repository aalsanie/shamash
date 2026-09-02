/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
