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

            // On fallback we return a normalized target string.
            assertTrue(out.contains("/x/y.txt"), "Expected fallback path to contain '/x/y.txt' but was '$out'")
            assertTrue(!out.contains("\\"), "Path must be forward-slash normalized: '$out'")
        }
    }
}
