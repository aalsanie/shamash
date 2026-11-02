/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.pipeline

import io.shamash.export.api.Exporters

/** Apply exceptions before baseline suppression so fingerprints reflect the filtered findings. */
object DefaultExportPipelineFactory {
    fun create(
        exceptionsPreprocessor: FindingPreprocessor? = null,
        baselinePreprocessor: FindingPreprocessor? = null,
    ): ExportOrchestrator {
        val preprocessors = ArrayList<FindingPreprocessor>(2)

        if (exceptionsPreprocessor != null) {
            preprocessors.add(exceptionsPreprocessor)
        }
        if (baselinePreprocessor != null) {
            preprocessors.add(baselinePreprocessor)
        }

        return ExportOrchestrator(
            reportBuilder = ReportBuilder(preprocessors),
            exporters = Exporters.createAll(),
        )
    }
}
