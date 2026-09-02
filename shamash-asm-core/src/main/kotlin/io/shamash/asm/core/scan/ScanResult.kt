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
package io.shamash.asm.core.scan

import io.shamash.asm.core.config.ValidationError
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
    /** Engine result (null when config validation failed). */
    val engine: EngineResult? = null,
) {
    val hasConfigErrors: Boolean get() = configErrors.isNotEmpty()
    val hasScanErrors: Boolean get() = scanErrors.isNotEmpty()
    val hasFactsErrors: Boolean get() = factsErrors.isNotEmpty()
    val hasEngineResult: Boolean get() = engine != null

    /** A successful run may still contain policy findings; success requires no validation or execution errors. */
    val isSuccess: Boolean get() = engine?.isSuccess == true && !hasConfigErrors
}
