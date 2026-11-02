/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.report.layout

import java.nio.file.Path

object ExportOutputLayout {
    const val DEFAULT_DIR_NAME: String = ".shamash"
    const val JSON_FILE_NAME: String = "shamash-report.json"
    const val SARIF_FILE_NAME: String = "shamash-report.sarif.json"
    const val XML_FILE_NAME: String = "shamash-report.xml"
    const val HTML_FILE_NAME: String = "shamash-report.html"

    const val FACTS_JSONL_GZ_FILE_NAME: String = "facts.jsonl.gz"

    // facts tend to be huge, always prefer jsonl.gz over json for facts unless the scanned project is small enough
    const val FACTS_JSON_FILE_NAME: String = "facts.json"
    const val ROLES_JSON_FILE_NAME: String = "roles.json"
    const val RULE_PLAN_JSON_FILE_NAME: String = "rule-plan.json"
    const val ANALYSIS_GRAPHS_JSON_FILE_NAME: String = "analysis-graphs.json"
    const val ANALYSIS_HOTSPOTS_JSON_FILE_NAME: String = "analysis-hotspots.json"
    const val ANALYSIS_SCORES_JSON_FILE_NAME: String = "analysis-scores.json"

    fun normalizeOutputDir(
        projectBasePath: Path,
        outputDir: Path?,
    ): Path {
        val base = outputDir ?: projectBasePath.resolve(DEFAULT_DIR_NAME)
        return base.normalize()
    }

    fun resolve(
        outputDir: Path,
        fileName: String,
    ): Path = outputDir.resolve(fileName)
}
