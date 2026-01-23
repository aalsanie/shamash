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
package io.shamash.asm.core.export.analysis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.asm.core.analysis.AnalysisResult
import io.shamash.asm.core.analysis.GraphAnalysisResult
import io.shamash.asm.core.analysis.HotspotsResult
import io.shamash.asm.core.analysis.ScoringResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Analysis sidecar exporters.
 *
 * Stage 2 exports three dedicated JSON artifacts:
 * - analysis-graphs.json
 * - analysis-hotspots.json
 * - analysis-scores.json
 */
object AnalysisExporter {
    private val mapper: ObjectMapper =
        jacksonObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)

    fun exportGraphs(
        graphs: GraphAnalysisResult,
        outputPath: Path,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ) {
        outputPath.parent?.createDirectories()
        val doc =
            AnalysisGraphsDocument(
                schemaId = "shamash.analysis.graphs",
                schemaVersion = 1,
                toolName = toolName,
                toolVersion = toolVersion,
                generatedAtEpochMillis = generatedAtEpochMillis,
                projectName = projectName,
                graphs = graphs,
            )

        Files.newBufferedWriter(outputPath).use { out ->
            out.write(mapper.writeValueAsString(doc))
            out.write("\n")
        }
    }

    fun exportHotspots(
        hotspots: HotspotsResult,
        outputPath: Path,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ) {
        outputPath.parent?.createDirectories()
        val doc =
            AnalysisHotspotsDocument(
                schemaId = "shamash.analysis.hotspots",
                schemaVersion = 1,
                toolName = toolName,
                toolVersion = toolVersion,
                generatedAtEpochMillis = generatedAtEpochMillis,
                projectName = projectName,
                hotspots = hotspots,
            )

        Files.newBufferedWriter(outputPath).use { out ->
            out.write(mapper.writeValueAsString(doc))
            out.write("\n")
        }
    }

    fun exportScores(
        scoring: ScoringResult,
        outputPath: Path,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ) {
        outputPath.parent?.createDirectories()
        val doc =
            AnalysisScoresDocument(
                schemaId = "shamash.analysis.scores",
                schemaVersion = 1,
                toolName = toolName,
                toolVersion = toolVersion,
                generatedAtEpochMillis = generatedAtEpochMillis,
                projectName = projectName,
                scoring = scoring,
            )

        Files.newBufferedWriter(outputPath).use { out ->
            out.write(mapper.writeValueAsString(doc))
            out.write("\n")
        }
    }

    /**
     * Export all available analysis sidecars next to the main report.
     *
     * Files are only written when the corresponding section exists in [result].
     */
    fun export(
        result: AnalysisResult,
        outputDir: Path,
        toolName: String,
        toolVersion: String,
        projectName: String,
        generatedAtEpochMillis: Long,
    ): AnalysisExportPaths {
        val graphsPath =
            result.graphs?.let {
                val p = ExportOutputLayout.resolve(outputDir, ExportOutputLayout.ANALYSIS_GRAPHS_JSON_FILE_NAME)
                exportGraphs(
                    graphs = it,
                    outputPath = p,
                    toolName = toolName,
                    toolVersion = toolVersion,
                    projectName = projectName,
                    generatedAtEpochMillis = generatedAtEpochMillis,
                )
                p
            }

        val hotspotsPath =
            result.hotspots?.let {
                val p = ExportOutputLayout.resolve(outputDir, ExportOutputLayout.ANALYSIS_HOTSPOTS_JSON_FILE_NAME)
                exportHotspots(
                    hotspots = it,
                    outputPath = p,
                    toolName = toolName,
                    toolVersion = toolVersion,
                    projectName = projectName,
                    generatedAtEpochMillis = generatedAtEpochMillis,
                )
                p
            }

        val scoresPath =
            result.scoring?.let {
                val p = ExportOutputLayout.resolve(outputDir, ExportOutputLayout.ANALYSIS_SCORES_JSON_FILE_NAME)
                exportScores(
                    scoring = it,
                    outputPath = p,
                    toolName = toolName,
                    toolVersion = toolVersion,
                    projectName = projectName,
                    generatedAtEpochMillis = generatedAtEpochMillis,
                )
                p
            }

        return AnalysisExportPaths(graphsPath = graphsPath, hotspotsPath = hotspotsPath, scoresPath = scoresPath)
    }
}

data class AnalysisExportPaths(
    val graphsPath: Path?,
    val hotspotsPath: Path?,
    val scoresPath: Path?,
)
