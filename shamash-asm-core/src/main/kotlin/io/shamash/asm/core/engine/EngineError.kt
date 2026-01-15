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
package io.shamash.asm.core.engine

/**
 * Engine-level errors (non-finding failures).
 * - config decode/validation issues (if surfaced here)
 * - facts extraction failures (bytecode parse failures)
 * - rule execution failure (unexpected exception)
 * - export/baseline IO failures
 */
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
