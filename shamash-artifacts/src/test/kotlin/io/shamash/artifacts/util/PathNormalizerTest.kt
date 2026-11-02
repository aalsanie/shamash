/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.util

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathNormalizerTest {
    @Test
    fun normalize_convertsBackslashes_stripsDriveLetters_andCollapsesSlashes() {
        assertEquals("/x/y/z", PathNormalizer.normalize("C:\\x\\y\\z"))
        assertEquals("/a/b", PathNormalizer.normalize("/a//b"))
        assertEquals("/a/b", PathNormalizer.normalize("C:/a//b"))
        assertEquals("/a/b/c", PathNormalizer.normalize("C:\\a\\b\\c"))
    }

    @Test
    fun relativizeOrNormalize_returnsStableRelativePath_whenTargetIsUnderBase() {
        val base = Paths.get("/tmp/project")
        val target = Paths.get("/tmp/project/src/main/kotlin/File.kt")

        val out = PathNormalizer.relativizeOrNormalize(base, target)
        assertEquals("src/main/kotlin/File.kt", out)
    }

    @Test
    fun relativizeOrNormalize_resolvesRelativeTargetsAgainstProjectBase() {
        val base = Files.createTempDirectory("shamash-path-base")
        val relative = Paths.get("build/classes/com/example/App.class")
        val absolute = base.resolve(relative)

        assertEquals("build/classes/com/example/App.class", PathNormalizer.relativizeOrNormalize(base, relative))
        assertEquals("build/classes/com/example/App.class", PathNormalizer.relativizeOrNormalize(base, absolute))
    }

    @Test
    fun relativizeOrNormalize_keepsRelativePathsOutsideProjectBase() {
        val base = Files.createTempDirectory("shamash-path-base")
        val relative = Paths.get("../external/Other.class")

        assertEquals("../external/Other.class", PathNormalizer.relativizeOrNormalize(base, relative))
        assertEquals("../external/Other.class", PathNormalizer.relativizeOrNormalize(base, base.resolve(relative)))
    }

    @Test
    fun relativizeOrNormalize_fallsBackToNormalizedTarget_whenRelativizeFails() {
        // Force an exception by mixing different FileSystem providers.
        val base: Path = Files.createTempDirectory("shamash-base")

        val zipFile = Files.createTempFile("shamash-path-normalizer", ".zip")
        ZipOutputStream(Files.newOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("x/y.txt"))
            zos.write("ok".toByteArray())
            zos.closeEntry()
        }

        val uri = URI.create("jar:" + zipFile.toUri())
        FileSystems.newFileSystem(uri, mapOf("create" to "false")).use { zipFs ->
            val target = zipFs.getPath("/x/y.txt")

            val out = PathNormalizer.relativizeOrNormalize(base, target)

            assertTrue(out.contains("/x/y.txt"), "Expected fallback path to contain '/x/y.txt' but was '$out'")
            assertTrue(!out.contains("\\"), "Path must be forward-slash normalized: '$out'")
        }
    }
}
