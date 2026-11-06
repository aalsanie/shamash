/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.cli

import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BaselineConfigEditPlanTest {
    @Test
    fun `config update is atomic and preserves unrelated temp files`() {
        val dir = Files.createTempDirectory("shamash-config-plan")
        try {
            val path = dir.resolve("asm.yml")
            Files.writeString(path, "version: 1\nbaseline:\n  mode: GENERATE\n  path: baseline.json\n")
            val sentinel = dir.resolve("asm.yml.tmp")
            Files.writeString(sentinel, "unrelated")
            BaselineConfigEditPlan.prepare(path, BaselineMode.GENERATE).apply()
            assertEquals("version: 1\nbaseline:\n  mode: VERIFY\n  path: baseline.json\n", Files.readString(path))
            assertEquals("unrelated", Files.readString(sentinel))
            Files.list(dir).use { files -> assertEquals(2, files.count()) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restoration replaces a generated baseline and can remove a new one`() {
        val dir = Files.createTempDirectory("shamash-baseline-restore")
        try {
            val path = dir.resolve("baseline.json")
            val previous = "previous".toByteArray()
            Files.writeString(path, "generated")
            Files.writeString(dir.resolve("baseline.json.tmp"), "unrelated")
            restoreBaseline(path, previous)
            assertEquals("previous", Files.readString(path))
            assertEquals("unrelated", Files.readString(dir.resolve("baseline.json.tmp")))

            restoreBaseline(path, null)
            assertEquals(false, Files.exists(path))
            assertEquals("unrelated", Files.readString(dir.resolve("baseline.json.tmp")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid input does not replace existing config`() {
        val dir = Files.createTempDirectory("shamash-config-plan-invalid")
        try {
            val path = dir.resolve("asm.yml")
            Files.writeString(path, "version: 1\nbaseline: { mode: GENERATE }\n")
            val previous = Files.readAllBytes(path)
            assertFailsWith<IllegalArgumentException> {
                BaselineConfigEditPlan.prepare(path, BaselineMode.GENERATE)
            }
            assertEquals(previous.toList(), Files.readAllBytes(path).toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
