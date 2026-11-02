/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine

data class EngineError(
    val code: Code,
    val message: String,
    val details: Map<String, String> = emptyMap(),
    val cause: Cause? = null,
) {
    enum class Code {
        CONFIG_INVALID,
        FACTS_EXTRACTION_FAILED,
        RULE_NOT_FOUND,
        RULE_EXECUTION_FAILED,
        BASELINE_FAILED,
        EXPORT_FAILED,
        INTERNAL_ERROR,
    }

    data class Cause(
        val type: String,
        val message: String? = null,
        val stack: String? = null,
    )

    companion object {
        fun configInvalid(
            message: String,
            details: Map<String, String> = emptyMap(),
            t: Throwable? = null,
        ): EngineError =
            EngineError(
                code = Code.CONFIG_INVALID,
                message = message,
                details = details,
                cause = t?.toCause(),
            )

        fun factsExtractionFailed(
            message: String,
            details: Map<String, String> = emptyMap(),
            t: Throwable? = null,
        ): EngineError =
            EngineError(
                code = Code.FACTS_EXTRACTION_FAILED,
                message = message,
                details = details,
                cause = t?.toCause(),
            )

        fun ruleNotFound(
            ruleId: String,
            message: String = "Rule not found: $ruleId",
        ): EngineError =
            EngineError(
                code = Code.RULE_NOT_FOUND,
                message = message,
                details = mapOf("ruleId" to ruleId),
                cause = null,
            )

        fun ruleExecutionFailed(
            ruleId: String,
            message: String,
            details: Map<String, String> = emptyMap(),
            t: Throwable? = null,
        ): EngineError =
            EngineError(
                code = Code.RULE_EXECUTION_FAILED,
                message = message,
                details =
                    LinkedHashMap<String, String>().apply {
                        put("ruleId", ruleId)
                        putAll(details)
                    },
                cause = t?.toCause(),
            )

        fun baselineFailed(
            message: String,
            details: Map<String, String> = emptyMap(),
            t: Throwable? = null,
        ): EngineError =
            EngineError(
                code = Code.BASELINE_FAILED,
                message = message,
                details = details,
                cause = t?.toCause(),
            )

        fun exportFailed(
            message: String,
            details: Map<String, String> = emptyMap(),
            t: Throwable? = null,
        ): EngineError =
            EngineError(
                code = Code.EXPORT_FAILED,
                message = message,
                details = details,
                cause = t?.toCause(),
            )

        fun internal(
            message: String,
            details: Map<String, String> = emptyMap(),
            t: Throwable? = null,
        ): EngineError =
            EngineError(
                code = Code.INTERNAL_ERROR,
                message = message,
                details = details,
                cause = t?.toCause(),
            )

        private fun Throwable.toCause(): Cause =
            Cause(
                type = this::class.qualifiedName ?: this::class.java.name,
                message = this.message,
                stack = this.stackTraceToString(),
            )
    }
}
