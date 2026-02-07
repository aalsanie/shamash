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

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.shamash.asm.core.analysis.AnalysisResult
import io.shamash.asm.core.analysis.GraphAnalysisResult
import io.shamash.asm.core.analysis.HotspotsResult
import io.shamash.asm.core.analysis.ScoringResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Reader utilities for analysis sidecar artifacts.
 *
 * This is used by:
 * - CLI (to print CI-friendly summaries)
 * - IntelliJ (to load the Analysis tab from exported JSON)
 */
object AnalysisSidecarReader {
    private val mapper: ObjectMapper =
        jacksonObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)
            // Forward/backward compatibility: analysis models may add computed helpers (e.g. `isEmpty`).
            // Sidecars should remain readable even when extra fields appear.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun readGraphs(path: Path): GraphAnalysisResult =
        Files
            .newBufferedReader(path)
            .use { it.readText() }
            .let { json -> mapper.readValue(json, AnalysisGraphsDocument::class.java) }
            .graphs

    fun readHotspots(path: Path): HotspotsResult =
        Files
            .newBufferedReader(path)
            .use { it.readText() }
            .let { json -> mapper.readValue(json, AnalysisHotspotsDocument::class.java) }
            .hotspots

    fun readScores(path: Path): ScoringResult =
        Files
            .newBufferedReader(path)
            .use { it.readText() }
            .let { json -> mapper.readValue(json, AnalysisScoresDocument::class.java) }
            .scoring

    fun tryReadGraphs(path: Path?): GraphAnalysisResult? {
        if (path == null) return null
        if (!path.exists() || !path.isRegularFile()) return null
        return readGraphs(path)
    }

    fun tryReadHotspots(path: Path?): HotspotsResult? {
        if (path == null) return null
        if (!path.exists() || !path.isRegularFile()) return null
        return readHotspots(path)
    }

    fun tryReadScores(path: Path?): ScoringResult? {
        if (path == null) return null
        if (!path.exists() || !path.isRegularFile()) return null
        return readScores(path)
    }

    fun readAll(
        graphsPath: Path?,
        hotspotsPath: Path?,
        scoresPath: Path?,
    ): AnalysisResult {
        val graphs = tryReadGraphs(graphsPath)
        val hotspots = tryReadHotspots(hotspotsPath)
        val scoring = tryReadScores(scoresPath)
        return AnalysisResult(graphs = graphs, hotspots = hotspots, scoring = scoring)
    }
}
