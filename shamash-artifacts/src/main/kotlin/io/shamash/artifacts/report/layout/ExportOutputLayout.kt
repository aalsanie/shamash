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
package io.shamash.artifacts.report.layout

import java.nio.file.Path

/**
 * Single source of truth for exported artifact names and layout.
 */
object ExportOutputLayout {
    const val DEFAULT_DIR_NAME: String = ".shamash"
    const val JSON_FILE_NAME: String = "shamash-report.json"
    const val SARIF_FILE_NAME: String = "shamash-report.sarif.json"
    const val XML_FILE_NAME: String = "shamash-report.xml"
    const val HTML_FILE_NAME: String = "shamash-report.html"

    /**
     * Sidecar artifacts (next to the main report formats).
     *
     * These files are intentionally named without a tool prefix to keep them
     * stable, portable, and easy to reference from IntelliJ/CLI consumers.
     */
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

    /** Resolve the path to a known exported artifact in [outputDir]. */
    fun resolve(
        outputDir: Path,
        fileName: String,
    ): Path = outputDir.resolve(fileName)
}
