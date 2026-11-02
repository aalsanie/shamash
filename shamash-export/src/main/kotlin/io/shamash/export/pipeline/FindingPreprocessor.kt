/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.pipeline

import io.shamash.artifacts.contract.Finding
import java.nio.file.Path

fun interface FindingPreprocessor {
    fun process(
        projectBasePath: Path,
        findings: List<Finding>,
    ): List<Finding>
}
