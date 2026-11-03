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

internal data class ApiPublicTypesParams(
    val max: Int,
    val includeNested: Boolean,
    val includeInterfaces: Boolean,
    val includeEnums: Boolean,
    val includeAnnotations: Boolean,
) {
    companion object {
        fun decode(
            raw: Map<String, Any?>,
            path: String,
        ): ApiPublicTypesParams {
            val p = Params.of(raw, path)
            val allowed = setOf("max", "includeNested", "includeInterfaces", "includeEnums", "includeAnnotations")
            val unknown = p.unknownKeys(allowed).sorted().firstOrNull()
            if (unknown != null) throw ParamError("$path.$unknown", "is not supported")
            return ApiPublicTypesParams(
                max = p.requireInt("max", min = 0),
                includeNested = p.optionalBoolean("includeNested") ?: true,
                includeInterfaces = p.optionalBoolean("includeInterfaces") ?: true,
                includeEnums = p.optionalBoolean("includeEnums") ?: true,
                includeAnnotations = p.optionalBoolean("includeAnnotations") ?: true,
            )
        }
    }
}
