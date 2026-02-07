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
package io.shamash.asm.core.scan

import io.shamash.asm.core.config.SchemaValidator
import io.shamash.asm.core.config.SchemaValidatorNetworkNt
import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import java.nio.file.Path

/**
 * Runner options.
 */
data class ScanOptions(
    /** Project root (used to resolve config and relative bytecode roots). */
    val projectBasePath: Path,
    /** Used for report metadata; defaults to [projectBasePath.fileName]. */
    val projectName: String = projectBasePath.fileName?.toString() ?: "project",
    /** If null, runner will use [io.shamash.asm.core.config.ProjectLayout.ASM_CONFIG_CANDIDATES]. */
    val configPath: Path? = null,
    /** Structural schema validator. */
    val schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
    /** Whether to include [io.shamash.asm.core.facts.query.FactIndex] inside the final result. */
    val includeFactsInResult: Boolean = false,
    /**
     * Force-enable facts export regardless of config.
     *
     * Used by CLI: `scan --export-facts`.
     */
    val exportFacts: Boolean = false,
    /**
     * Optional override for facts export format when [exportFacts] is true.
     */
    val factsFormatOverride: ExportFactsFormat? = null,
)
