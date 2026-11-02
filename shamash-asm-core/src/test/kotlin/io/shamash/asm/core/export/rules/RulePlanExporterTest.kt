/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
