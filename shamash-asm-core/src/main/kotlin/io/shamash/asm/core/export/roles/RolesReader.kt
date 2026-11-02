/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.export.roles

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object RolesReader {
    private val mapper = jacksonObjectMapper()

    fun read(path: Path): RolesJsonDocument {
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { r ->
            return mapper.readValue(r)
        }
    }
}
