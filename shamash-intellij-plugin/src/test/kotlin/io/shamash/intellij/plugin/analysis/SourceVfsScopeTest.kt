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
package io.shamash.intellij.plugin.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceVfsScopeTest {
    private val scope =
        SourceVfsScope(
            listOf(
                SourceVfsScope.Root("/workspace/src/main/java", true),
                SourceVfsScope.Root("/workspace/src/main/kotlin", true),
                SourceVfsScope.Root("/workspace/src/main/java/generated", false),
                SourceVfsScope.Root("/workspace/src/main/java/excluded", false),
                SourceVfsScope.Root("/workspace/src/test/java", false),
                SourceVfsScope.Root("/workspace/src/test/kotlin", false),
            ),
        )

    @Test
    fun `directory changes intersecting production roots are relevant`() {
        for (path in listOf(
            "/workspace",
            "/workspace/src",
            "/workspace/src/main",
            "/workspace/src/main/java",
            "/workspace/src/main/java/a",
            "/workspace/src/main/kotlin/a/b",
        )) {
            assertTrue(scope.affects(path, directory = true), path)
        }
    }

    @Test
    fun `source root siblings and unrelated project directories are excluded`() {
        for (path in listOf(
            "/workspace/src/main/java2",
            "/workspace/src/main/kotlin2",
            "/workspace/reports",
            "/other-project/src/main/java",
            "/workspace2",
        )) {
            assertFalse(scope.affects(path, directory = true), path)
        }
    }

    @Test
    fun `test and generated directories do not invalidate production results`() {
        for (path in listOf(
            "/workspace/src/test/java/a",
            "/workspace/src/test/kotlin/a",
            "/workspace/src/main/java/generated/a",
            "/workspace/src/main/java/excluded/a",
        )) {
            assertFalse(scope.affects(path, directory = true), path)
            assertFalse(scope.affects("$path/Nested.java", directory = false), path)
        }
    }

    @Test
    fun `java and kotlin files are matched only under eligible roots`() {
        assertTrue(scope.affects("/workspace/src/main/java/a/One.java", directory = false))
        assertTrue(scope.affects("/workspace/src/main/kotlin/a/One.kt", directory = false))
        assertFalse(scope.affects("/workspace/src/main/java/a/One.kts", directory = false))
        assertFalse(scope.affects("/workspace/src/main/java/a/One.txt", directory = false))
        assertFalse(scope.affects("/workspace/src/main/java2/One.java", directory = false))
        assertFalse(scope.affects("/workspace/src/test/java/One.java", directory = false))
        assertFalse(scope.affects("/workspace/reports/One.kt", directory = false))
    }

    @Test
    fun `old and new directory paths independently detect entering or leaving a root`() {
        val oldInside = "/workspace/src/main/java/a"
        val movedOutside = "/workspace/elsewhere/a"
        assertTrue(scope.affects(oldInside, directory = true))
        assertFalse(scope.affects(movedOutside, directory = true))
        val oldOutside = "/workspace/elsewhere/b"
        val movedInside = "/workspace/src/main/java/b"
        assertFalse(scope.affects(oldOutside, directory = true))
        assertTrue(scope.affects(movedInside, directory = true))
    }

    @Test
    fun `normalized windows paths require complete directory segments`() {
        val windows =
            SourceVfsScope(
                listOf(
                    SourceVfsScope.Root("C:\\workspace\\src\\main\\java", true),
                    SourceVfsScope.Root("C:\\workspace\\src\\test\\java", false),
                ),
            )
        assertTrue(windows.affects("C:\\workspace\\src\\main", directory = true))
        assertTrue(windows.affects("C:\\workspace\\src\\main\\java\\demo", directory = true))
        assertTrue(windows.affects("C:\\workspace\\src\\main\\java\\Demo.java", directory = false))
        assertFalse(windows.affects("C:\\workspace\\src\\main\\java2", directory = true))
        assertFalse(windows.affects("C:\\workspace\\src\\test\\java\\Demo.java", directory = false))
    }

    @Test
    fun `source event batches invalidate once and reset for the next batch`() {
        var changes = 0
        val batch = SourceVfsBatch { changes++ }

        batch.before(false)
        batch.after(false)
        assertEquals(0, changes)

        batch.before(true)
        assertEquals(1, changes)
        batch.after(true)
        assertEquals(1, changes)

        batch.before(false)
        batch.after(true)
        assertEquals(2, changes)

        batch.before(true)
        batch.after(false)
        assertEquals(3, changes)

        batch.before(false)
        batch.after(false)
        assertEquals(3, changes)
    }

    @Test
    fun `empty scope cannot invalidate any path`() {
        val empty = SourceVfsScope(emptyList())
        assertFalse(empty.affects("/workspace/src/main/java/A.java", directory = false))
        assertFalse(empty.affects("/workspace/src/main", directory = true))
    }
}
