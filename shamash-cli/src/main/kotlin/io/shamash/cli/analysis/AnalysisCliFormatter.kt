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
package io.shamash.cli.analysis

import io.shamash.asm.core.analysis.AnalysisResult
import io.shamash.asm.core.analysis.HotspotEntry

internal object AnalysisCliFormatter {
    fun summaryLines(
        analysis: AnalysisResult,
        topCycles: Int = 5,
        topHotspots: Int = 5,
        topScores: Int = 5,
    ): List<String> {
        if (analysis.isEmpty) return listOf("Analysis     : (none)")

        val out = ArrayList<String>(64)

        analysis.graphs?.let { g ->
            // Keep section headers stable for tests / CI logs.
            out += "Graphs       : nodes=${g.nodeCount} edges=${g.edgeCount} scc=${g.sccCount} cyclicScc=${g.cyclicSccs.size}"

            val cycles =
                when {
                    g.representativeCycles.isNotEmpty() -> g.representativeCycles
                    g.cyclicSccs.isNotEmpty() -> g.cyclicSccs.map { it + it.first() }
                    else -> emptyList()
                }
            if (cycles.isNotEmpty()) {
                out += "Top cycles   :"
                cycles.take(topCycles).forEachIndexed { idx, c ->
                    out += "  ${idx + 1}) ${c.joinToString(" -> ")}"
                }
            }
        }

        analysis.hotspots?.let { hs ->
            if (hs.classHotspots.isNotEmpty()) {
                out += "Hotspots     : classes"
                hs.classHotspots.take(topHotspots).forEachIndexed { idx, e ->
                    out += "  ${idx + 1}) ${formatHotspot(e)}"
                }
            }
            if (hs.packageHotspots.isNotEmpty()) {
                out += "Hotspots     : packages"
                hs.packageHotspots.take(topHotspots).forEachIndexed { idx, e ->
                    out += "  ${idx + 1}) ${formatHotspot(e)}"
                }
            }
        }

        analysis.scoring?.let { sc ->
            sc.godClass?.let { god ->
                if (god.rows.isNotEmpty()) {
                    out += "Scores       : god-class"
                    god.rows.take(topScores).forEachIndexed { idx, r ->
                        out += "  ${idx + 1}) ${r.classFqn} score=${fmtScore(r.score)} band=${r.band.name}"
                    }
                }
            }
            sc.overall?.let { ov ->
                if (ov.rows.isNotEmpty()) {
                    out += "Scores       : packages"
                    ov.rows.take(topScores).forEachIndexed { idx, r ->
                        out += "  ${idx + 1}) ${r.packageName} score=${fmtScore(r.score)} band=${r.band.name}"
                    }
                }
            }
        }

        return out
    }

    private fun formatHotspot(e: HotspotEntry): String {
        val reasons =
            e.reasons.joinToString(", ") { r ->
                "${r.metric.name}=${r.value}(#${r.rank})"
            }
        return "${e.id} [$reasons]"
    }

    private fun fmtScore(v: Double): String {
        if (!v.isFinite()) return "0.000"
        return String.format("%.3f", v.coerceIn(0.0, 1.0))
    }
}
