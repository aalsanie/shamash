/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.scan

import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.EngineResult
import io.shamash.asm.core.facts.FactsError
import io.shamash.asm.core.scan.bytecode.BytecodeOrigin
import java.nio.file.Path

data class ScanResult(
    val options: ScanOptions,
    val appliedOverrides: RunOverrides? = null,
    val configPath: Path? = null,
    val config: ShamashAsmConfigV1? = null,
    val configErrors: List<ValidationError> = emptyList(),
    val scanErrors: List<ScanError> = emptyList(),
    val origins: List<BytecodeOrigin> = emptyList(),
    val classUnits: Int = 0,
    /** True when [io.shamash.asm.core.config.schema.v1.model.ScanConfig.maxClasses] truncated the scan. */
    val truncated: Boolean = false,
    /** Facts extraction errors (best-effort; facts may still be produced). */
    val factsErrors: List<FactsError> = emptyList(),
    /** Null when execution stopped before the engine could run. */
    val engine: EngineResult? = null,
) {
    val hasConfigErrors: Boolean get() = configErrors.any { it.severity == ValidationSeverity.ERROR }
    val hasConfigWarnings: Boolean get() = configErrors.any { it.severity == ValidationSeverity.WARNING }
    val hasScanErrors: Boolean get() = scanErrors.isNotEmpty()
    val hasFactsErrors: Boolean get() = factsErrors.isNotEmpty()
    val hasEngineResult: Boolean get() = engine != null

    /** A successful run may still contain policy findings; success requires no validation or execution errors. */
    val isSuccess: Boolean
        get() = engine?.isSuccess == true && !hasConfigErrors && !hasScanErrors && !hasFactsErrors && !truncated
}
