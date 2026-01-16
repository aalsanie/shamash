/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.artifacts.util.glob

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobMatcherTest {
    @Test
    fun matches_supportsStarAndQuestionMark() {
        assertTrue(GlobMatcher.matches("*.kt", "A.kt"))
        assertTrue(GlobMatcher.matches("file?.txt", "file1.txt"))
        assertFalse(GlobMatcher.matches("file?.txt", "file10.txt"))
        assertFalse(GlobMatcher.matches("*.kt", "A.java"))
    }

    @Test
    fun matches_supportsDoubleStarAcrossDirectories() {
        assertTrue(GlobMatcher.matches("**/*.kt", "src/main/kotlin/App.kt"))
        assertTrue(GlobMatcher.matches("**/*.kt", "/src/main/kotlin/App.kt"))
        assertFalse(GlobMatcher.matches("**/*.kt", "src/main/kotlin/App.java"))
    }

    @Test
    fun matches_relativeGlobMatchesAnywhereInPath() {
        // Relative-ish globs should match anywhere in the path via the **/ fallback.
        assertTrue(GlobMatcher.matches("foo/bar.txt", "a/b/foo/bar.txt"))
        assertTrue(GlobMatcher.matches("foo/bar.txt", "/a/b/foo/bar.txt"))

        // Must not overmatch.
        assertFalse(GlobMatcher.matches("foo/bar.txt", "a/b/foo/bar.txt.bak"))
        assertFalse(GlobMatcher.matches("bar", "a/b/foobar"))
    }

    @Test
    fun matches_normalizesWindowsStylePaths() {
        assertTrue(GlobMatcher.matches("**/*.kt", "C:\\proj\\src\\Main.kt"))
        assertTrue(GlobMatcher.matches("proj/src/*.kt", "C:\\proj\\src\\Main.kt"))
        assertFalse(GlobMatcher.matches("proj/src/*.kt", "C:\\proj\\src\\Main.java"))
    }
}
