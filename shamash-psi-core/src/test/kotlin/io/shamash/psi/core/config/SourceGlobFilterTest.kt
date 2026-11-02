/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceGlobFilterTest {
    @Test
    fun `empty includes allow files when there are no exclusions`() {
        assertTrue(SourceGlobFilter.matches("/project/src/main/java/App.java", emptyList(), emptyList()))
        assertTrue(SourceGlobFilter.matches("/project/src/main/kotlin/App.kt", emptyList(), emptyList()))
    }

    @Test
    fun `empty includes still respect exclusions`() {
        val exclude = listOf("**/build/**", "**/generated/**")

        assertTrue(SourceGlobFilter.matches("/project/src/main/java/App.java", emptyList(), exclude))
        assertFalse(SourceGlobFilter.matches("/project/build/classes/App.java", emptyList(), exclude))
        assertFalse(SourceGlobFilter.matches("/project/generated/App.java", emptyList(), exclude))
    }

    @Test
    fun `nonempty includes restrict the source set`() {
        val include = listOf("src/main/kotlin/**")

        assertTrue(SourceGlobFilter.matches("/project/src/main/kotlin/App.kt", include, emptyList()))
        assertFalse(SourceGlobFilter.matches("/project/src/test/kotlin/App.kt", include, emptyList()))
        assertFalse(SourceGlobFilter.matches("/project/src/main/java/App.java", include, emptyList()))
    }

    @Test
    fun `exclusions take precedence over matching includes`() {
        val include = listOf("src/**")
        val exclude = listOf("**/generated/**")

        assertFalse(SourceGlobFilter.matches("/project/src/generated/Api.kt", include, exclude))
        assertTrue(SourceGlobFilter.matches("/project/src/main/kotlin/App.kt", include, exclude))
    }

    @Test
    fun `windows separators are normalized consistently`() {
        val include = listOf("src/main/java/**")

        assertTrue(SourceGlobFilter.matches("C:\\project\\src\\main\\java\\App.java", include, emptyList()))
        assertFalse(SourceGlobFilter.matches("C:\\project\\src\\test\\java\\App.java", include, emptyList()))
    }
}
