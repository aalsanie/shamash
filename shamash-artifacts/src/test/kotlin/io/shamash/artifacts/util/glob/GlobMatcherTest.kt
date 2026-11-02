/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
        assertTrue(GlobMatcher.matches("foo/bar.txt", "a/b/foo/bar.txt"))
        assertTrue(GlobMatcher.matches("foo/bar.txt", "/a/b/foo/bar.txt"))

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
