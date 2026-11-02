/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.scan

import io.shamash.asm.core.config.SchemaValidator
import io.shamash.asm.core.config.SchemaValidatorNetworkNt
import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import java.nio.file.Path

data class ScanOptions
    @JvmOverloads
    constructor(
        /** Project root (used to resolve config and relative bytecode roots). */
        val projectBasePath: Path,
        /** Used for report metadata; defaults to `projectBasePath.fileName`. */
        val projectName: String = projectBasePath.fileName?.toString() ?: "project",
        /** If null, runner checks project config candidates and then uses the built-in discovery config. */
        val configPath: Path? = null,
        val schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
        val includeFactsInResult: Boolean = false,
        /** Forces facts export regardless of the config setting. */
        val exportFacts: Boolean = false,
        /**
         * Optional override for facts export format when [exportFacts] is true.
         */
        val factsFormatOverride: ExportFactsFormat? = null,
    )
