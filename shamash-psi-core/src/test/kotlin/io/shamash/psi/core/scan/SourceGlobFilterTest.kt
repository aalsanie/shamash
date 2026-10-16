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
package io.shamash.psi.core.scan

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
