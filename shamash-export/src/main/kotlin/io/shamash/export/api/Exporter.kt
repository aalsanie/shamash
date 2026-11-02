/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.api

import io.shamash.artifacts.report.schema.v1.ExportedReport
import java.nio.file.Path

/** Write reports without mutating them or reordering findings; keep formatting deterministic. */
interface Exporter {
    fun export(
        report: ExportedReport,
        outputDir: Path,
    )
}
