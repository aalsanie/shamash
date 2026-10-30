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
package io.shamash.asm.core.export.rules

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RulePlanExporterTest {
    @Test
    fun `failed replacement preserves existing directory and cleans temporary file`() {
        val parent = Files.createTempDirectory("shamash-rule-plan-export")
        try {
            val target = Files.createDirectory(parent.resolve("rule-plan.json"))
            val sentinel = Files.writeString(target.resolve("sentinel"), "preserved")

            assertFailsWith<Exception> {
                RulePlanExporter.export(
                    entries = emptyList(),
                    outputPath = target,
                    toolName = "test",
                    toolVersion = "0.0.0",
                    projectName = "demo",
                    generatedAtEpochMillis = 42L,
                )
            }

            assertEquals("preserved", Files.readString(sentinel))
            Files.list(parent).use { files ->
                assertEquals(listOf("rule-plan.json"), files.map { it.fileName.toString() }.toList())
            }
            assertTrue(Files.isDirectory(target))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
