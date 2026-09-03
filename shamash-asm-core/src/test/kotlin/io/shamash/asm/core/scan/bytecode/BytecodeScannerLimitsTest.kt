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
package io.shamash.asm.core.scan.bytecode

import io.shamash.asm.core.config.schema.v1.model.BytecodeConfig
import io.shamash.asm.core.config.schema.v1.model.GlobSet
import io.shamash.asm.core.config.schema.v1.model.ScanConfig
import io.shamash.asm.core.config.schema.v1.model.ScanScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BytecodeScannerLimitsTest {
    @Test
    fun `exact class limit includes default package and ignores resources and empty jars`() {
        withProject { project ->
            write(project, "build/classes/App.class", byteArrayOf(1))
            write(project, "build/classes/readme.txt", byteArrayOf(2))
            jar(project, "lib/resources.jar", "readme.txt" to byteArrayOf(3))

            val result = BytecodeScanner().scan(project, bytecode(), limits(maxClasses = 1))

            assertEquals(listOf("build/classes/App.class"), result.units.map { it.originId })
            assertFalse(result.truncated)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
        }
    }

    @Test
    fun `overlapping roots and nested output directories are scanned once`() {
        withProject { project ->
            write(project, "build/classes/main/com/example/App.class", byteArrayOf(1))
            val config = bytecode().copy(roots = listOf("build/classes/main/com", ".", "build/classes/main"))

            val result = BytecodeScanner().scan(project, config, limits(maxClasses = 1))

            assertEquals(1, result.units.size)
            assertEquals(listOf("build/classes"), result.origins.map { it.stablePath })
            assertFalse(result.truncated)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
        }
    }

    @Test
    fun `jar resources after the final class do not imply truncation`() {
        withProject { project ->
            val path = jar(project, "lib/input.jar", "App.class" to byteArrayOf(1), "last.txt" to byteArrayOf(2))

            val result = BytecodeScanner().scan(project, bytecode(), limits(maxClasses = 1))

            assertEquals(1, result.units.size)
            assertFalse(result.truncated)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            Files.delete(path)
        }
    }

    @Test
    fun `another jar class marks truncation and closes the jar`() {
        withProject { project ->
            val path =
                jar(
                    project,
                    "lib/input.jar",
                    "App.class" to byteArrayOf(1),
                    "resource.txt" to byteArrayOf(2),
                    "Other.class" to byteArrayOf(3),
                )

            val result = BytecodeScanner().scan(project, bytecode(), limits(maxClasses = 1))

            assertEquals(1, result.units.size)
            assertTrue(result.truncated)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            Files.delete(path)
        }
    }

    @Test
    fun `excluded output subtrees stay excluded without hiding matching jars`() {
        withProject { project ->
            write(project, "build/classes/main/App.class", byteArrayOf(1))
            write(project, "build/classes/main/generated/deep/Skipped.class", byteArrayOf(2))
            jar(project, "build/classes/main/generated/library.jar", "Library.class" to byteArrayOf(3))
            for (exclude in listOf("**/generated", "**/generated/**")) {
                val config =
                    bytecode().copy(
                        outputsGlobs = GlobSet(include = listOf("build/classes/**"), exclude = listOf(exclude)),
                    )

                val result = BytecodeScanner().scan(project, config, limits(maxClasses = 2))

                assertEquals(
                    listOf("build/classes/main/App.class", "build/classes/main/generated/library.jar!/Library.class"),
                    result.units.map { it.originId },
                )
                assertFalse(result.truncated)
                assertTrue(result.errors.isEmpty(), result.errors.toString())

                val nested = config.copy(roots = listOf("build/classes/main/generated/deep"))
                assertTrue(BytecodeScanner().scan(project, nested, limits()).units.isEmpty())
            }
        }
    }

    @Test
    fun `excluded class files do not consume the class limit`() {
        withProject { project ->
            write(project, "build/classes/App.class", byteArrayOf(1))
            write(project, "build/classes/Generated.class", byteArrayOf(2))
            val config =
                bytecode().copy(
                    outputsGlobs = GlobSet(include = listOf("build/classes/**"), exclude = listOf("**/Generated.class")),
                )

            val result = BytecodeScanner().scan(project, config, limits(maxClasses = 1))

            assertEquals(listOf("build/classes/App.class"), result.units.map { it.originId })
            assertFalse(result.truncated)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
        }
    }

    @Test
    fun `class byte limit accepts the boundary and rejects larger files`() {
        withProject { project ->
            val bytes = ByteArray(16) { it.toByte() }
            write(project, "build/classes/App.class", bytes)
            write(project, "build/classes/Large.class", ByteArray(17))

            val result = BytecodeScanner().scan(project, bytecode(), limits(maxClasses = 1, maxClassBytes = 16))

            assertEquals(listOf("build/classes/App.class"), result.units.map { it.originId })
            assertContentEquals(bytes, result.units.single().bytes)
            assertFalse(result.truncated)
            assertTrue(
                result.errors
                    .single()
                    .message
                    .contains("maxClassBytes=16"),
            )
            assertEquals("build/classes/Large.class", result.errors.single().path)
        }
    }

    @Test
    fun `jar entry limit uses uncompressed size and closes rejected input`() {
        withProject { project ->
            val path = jar(project, "lib/input.jar", "Large.class" to ByteArray(16384))
            val result =
                BytecodeScanner().scan(
                    project,
                    bytecode(),
                    limits(maxJarBytes = Files.size(path).toInt(), maxClassBytes = 16),
                )

            assertTrue(result.units.isEmpty())
            assertFalse(result.truncated)
            assertTrue(
                result.errors
                    .single()
                    .message
                    .contains("maxClassBytes=16"),
            )
            assertEquals("lib/input.jar!/Large.class", result.errors.single().path)
            Files.delete(path)
        }
    }

    @Test
    fun `jar byte limit accepts the boundary and rejects larger containers`() {
        withProject { project ->
            val path = jar(project, "lib/input.jar", "App.class" to byteArrayOf(1))
            val size = Files.size(path).toInt()
            val accepted = BytecodeScanner().scan(project, bytecode(), limits(maxJarBytes = size))
            assertEquals(1, accepted.units.size)
            assertTrue(accepted.errors.isEmpty(), accepted.errors.toString())

            val rejected = BytecodeScanner().scan(project, bytecode(), limits(maxJarBytes = size - 1))
            assertTrue(rejected.units.isEmpty())
            assertTrue(
                rejected.errors
                    .single()
                    .message
                    .contains("maxJarBytes="),
            )
            Files.delete(path)
        }
    }

    @Test
    fun `invalid jars report errors and release their file handles`() {
        withProject { project ->
            val path = write(project, "lib/broken.jar", byteArrayOf(1, 2, 3))
            val result = BytecodeScanner().scan(project, bytecode(), limits())

            assertTrue(result.units.isEmpty())
            assertTrue(
                result.errors
                    .single()
                    .message
                    .contains("Failed to read jar"),
            )
            Files.delete(path)
        }
    }

    @Test
    fun `programmatic scan limits reject nonpositive values`() {
        withProject { project ->
            for (value in listOf(0, -1)) {
                for (config in listOf(limits(maxClasses = value), limits(maxJarBytes = value), limits(maxClassBytes = value))) {
                    assertFailsWith<IllegalArgumentException> { BytecodeScanner().scan(project, bytecode(), config) }
                }
            }
        }
    }

    @Test
    fun `project classes take precedence over external origins at the limit`() {
        withProject { parent ->
            val project = Files.createDirectories(parent.resolve("project"))
            val external = Files.createDirectories(parent.resolve("external/build/classes"))
            write(project, "build/classes/Project.class", byteArrayOf(1))
            write(external, "External.class", byteArrayOf(2))
            val config = bytecode().copy(roots = listOf(external.toString(), "."))

            val result =
                BytecodeScanner().scan(
                    project,
                    config,
                    limits(maxClasses = 1).copy(scope = ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS),
                )

            assertEquals(listOf("build/classes/Project.class"), result.units.map { it.originId })
            assertTrue(result.truncated)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
        }
    }

    @Test
    fun `external output ancestors do not hide project classes or change their priority`() {
        withProject { parent ->
            val project = Files.createDirectories(parent.resolve("project"))
            write(project, "App.class", byteArrayOf(1))
            write(parent, "External.class", byteArrayOf(2))
            val config =
                bytecode().copy(
                    roots = listOf(parent.toString()),
                    outputsGlobs = GlobSet(include = listOf("**"), exclude = emptyList()),
                )

            val projectOnly = BytecodeScanner().scan(project, config, limits(maxClasses = 1))
            assertEquals(listOf("App.class"), projectOnly.units.map { it.originId })
            assertFalse(projectOnly.truncated)
            assertTrue(projectOnly.errors.isEmpty(), projectOnly.errors.toString())

            val withExternal =
                BytecodeScanner().scan(
                    project,
                    config,
                    limits(maxClasses = 1).copy(scope = ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS),
                )
            assertEquals(listOf("App.class"), withExternal.units.map { it.originId })
            assertTrue(withExternal.truncated)
            assertTrue(withExternal.errors.isEmpty(), withExternal.errors.toString())
        }
    }

    private fun bytecode(): BytecodeConfig =
        BytecodeConfig(
            roots = listOf("."),
            outputsGlobs = GlobSet(include = listOf("build/classes/**"), exclude = emptyList()),
            jarGlobs = GlobSet(include = listOf("**/*.jar"), exclude = emptyList()),
        )

    private fun limits(
        maxClasses: Int? = null,
        maxJarBytes: Int? = null,
        maxClassBytes: Int? = null,
    ): ScanConfig =
        ScanConfig(
            scope = ScanScope.PROJECT_ONLY,
            followSymlinks = false,
            maxClasses = maxClasses,
            maxJarBytes = maxJarBytes,
            maxClassBytes = maxClassBytes,
        )

    private fun write(
        project: Path,
        relativePath: String,
        bytes: ByteArray,
    ): Path {
        val path = project.resolve(relativePath)
        Files.createDirectories(path.parent)
        return Files.write(path, bytes)
    }

    private fun jar(
        project: Path,
        relativePath: String,
        vararg entries: Pair<String, ByteArray>,
    ): Path {
        val path = project.resolve(relativePath)
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }

    private fun withProject(action: (Path) -> Unit) {
        val project = Files.createTempDirectory("shamash-scan-limits")
        try {
            action(project)
        } finally {
            project.toFile().deleteRecursively()
        }
    }
}
