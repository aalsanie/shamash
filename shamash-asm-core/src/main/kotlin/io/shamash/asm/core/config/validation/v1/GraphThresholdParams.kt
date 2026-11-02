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

internal object GraphThresholdParams {
    fun cycles(
        raw: Map<String, Any?>,
        path: String,
    ): Int = validated(raw, path, "maxCycles") { it.requireInt("maxCycles", min = 0) }

    fun edges(
        raw: Map<String, Any?>,
        path: String,
    ): Int = validated(raw, path, "maxEdges") { it.requireInt("maxEdges", min = 0) }

    fun density(
        raw: Map<String, Any?>,
        path: String,
    ): Double = validated(raw, path, "maxDensity") { it.requireDouble("maxDensity", min = 0.0, max = 1.0) }

    fun errors(
        raw: Map<String, Any?>,
        path: String,
        key: String,
    ): List<ValidationError> =
        try {
            when (key) {
                "maxCycles" -> cycles(raw, path)
                "maxEdges" -> edges(raw, path)
                "maxDensity" -> density(raw, path)
                else -> error("Unsupported graph threshold: $key")
            }
            emptyList()
        } catch (e: ParamError) {
            listOf(ValidationError(e.at, e.message, ValidationSeverity.ERROR))
        }

    private inline fun <T> validated(
        raw: Map<String, Any?>,
        path: String,
        key: String,
        parse: (Params) -> T,
    ): T {
        val params = Params.of(raw, path)
        if (params.has("max")) throw ParamError("$path.max", "is not supported; use '$key'")
        val unknown = params.unknownKeys(setOf(key)).sorted().firstOrNull()
        if (unknown != null) throw ParamError("$path.$unknown", "is not supported; allowed parameter: '$key'")
        return parse(params)
    }
}
