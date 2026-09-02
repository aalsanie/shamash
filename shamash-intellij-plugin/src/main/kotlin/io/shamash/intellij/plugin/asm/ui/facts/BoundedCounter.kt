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
package io.shamash.intellij.plugin.asm.ui.facts

/** Caps distinct keys to bound memory while streaming large fact exports. */
class BoundedCounter<K>(
    private val maxKeys: Int,
) {
    private val counts = LinkedHashMap<K, Long>(minOf(maxKeys, 16))
    private var droppedIncrements: Long = 0

    fun increment(
        key: K,
        by: Long = 1,
    ) {
        if (by <= 0) return

        val existing = counts[key]
        if (existing != null) {
            counts[key] = existing + by
            return
        }

        if (counts.size < maxKeys) {
            counts[key] = by
        } else {
            droppedIncrements += by
        }
    }

    fun dropped(): Long = droppedIncrements

    fun size(): Int = counts.size

    fun top(
        n: Int,
        keyToStableString: (K) -> String = { it.toString() },
    ): List<Pair<K, Long>> {
        if (n <= 0) return emptyList()
        return counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<K, Long>> { it.value }
                    .thenBy { keyToStableString(it.key) },
            ).take(n)
            .map { it.key to it.value }
    }

    fun snapshot(): Map<K, Long> = counts.toMap()
}
