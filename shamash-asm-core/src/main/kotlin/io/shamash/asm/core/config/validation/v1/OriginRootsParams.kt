/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.config.validation.v1

import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object OriginRootsParams {
    fun roots(
        raw: Map<String, Any?>,
        path: String,
    ): List<String> {
        val params = Params.of(raw, path)
        val unknown = params.unknownKeys(setOf("roots")).sorted().firstOrNull()
        if (unknown != null) throw ParamError("$path.$unknown", "is not supported; use 'roots' (filesystem paths)")
        return params.requireStringList("roots", nonEmpty = true).also { values ->
            values.forEachIndexed { index, value ->
                if (value.isBlank()) throw ParamError("$path.roots[$index]", "must be a non-empty path")
                try {
                    Path.of(value)
                } catch (e: InvalidPathException) {
                    throw ParamError("$path.roots[$index]", "must be a valid filesystem path")
                }
            }
        }
    }

    fun errors(
        raw: Map<String, Any?>,
        path: String,
    ): List<ValidationError> =
        try {
            roots(raw, path)
            emptyList()
        } catch (e: ParamError) {
            listOf(ValidationError(e.at, e.message, ValidationSeverity.ERROR))
        }
}
