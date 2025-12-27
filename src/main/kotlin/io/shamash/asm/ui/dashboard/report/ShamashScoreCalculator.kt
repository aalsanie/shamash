/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.asm.ui.dashboard.report

import io.shamash.asm.model.Severity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic scoring.
 *
 * - 100 must be possible.
 * - INFO/NONE do not affect score.
 * - Normalized by project size so big repos aren't doomed.
 * - Avoid double counting across categories.
 * - Classify findings by *id* first (stable), then title as fallback.
 */
object ShamashScoreCalculator {
    val EMPTY_HOTSPOTS =
        HotspotsSnapshot(
            topGodScores = emptyList(),
            topFanOut = emptyList(),
            topDepth = emptyList(),
        )

    data class ScoreExplain(
        val category: String,
        val penalty: Int,
        val summary: String,
    )

    data class ScoreResult(
        val overall: Int,
        val structural: Int,
        val coupling: Int,
        val complexity: Int,
        val layering: Int,
        val explain: List<ScoreExplain>,
    )

    interface FindingLike {
        val id: String
        val severity: Severity
        val title: String
    }

    data class HotspotsSnapshot(
        val topGodScores: List<Int>,
        val topFanOut: List<Int>,
        val topDepth: List<Int>,
    )

    fun compute(
        classCount: Int,
        findings: List<FindingLike>,
        hotspots: HotspotsSnapshot,
    ): ScoreResult {
        val sizeFactor = normalizeSizeFactor(classCount)

        val structuralFindings = findings.filter { isStructural(it) }
        val layeringFindings = findings.filter { isLayering(it) }
        val couplingFindings = findings.filter { isCoupling(it) }
        // complexity comes from hotspots snapshot ONLY - so you can still score 100 if clean
        val complexityPenalty = penaltyFromHotspots(hotspots, classCount)

        val structuralPenalty = penaltyFromFindings(structuralFindings, sizeFactor, category = "Structural")
        val layeringPenalty = penaltyFromFindings(layeringFindings, sizeFactor, category = "Layering")
        val couplingPenalty = penaltyFromFindings(couplingFindings, sizeFactor, category = "Coupling")

        val structural = clamp100(100 - structuralPenalty.value)
        val layering = clamp100(100 - layeringPenalty.value)
        val coupling = clamp100(100 - couplingPenalty.value)
        val complexity = clamp100(100 - complexityPenalty.value)

        val overall =
            clamp100(
                (
                    0.35 * structural +
                        0.25 * layering +
                        0.20 * coupling +
                        0.20 * complexity
                ).roundToInt(),
            )

        val explain =
            listOf(
                ScoreExplain("Structural", structuralPenalty.value, structuralPenalty.summary),
                ScoreExplain("Layering", layeringPenalty.value, layeringPenalty.summary),
                ScoreExplain("Coupling", couplingPenalty.value, couplingPenalty.summary),
                ScoreExplain("Complexity", complexityPenalty.value, complexityPenalty.summary),
            )

        return ScoreResult(
            overall = overall,
            structural = structural,
            coupling = coupling,
            complexity = complexity,
            layering = layering,
            explain = explain,
        )
    }

    private data class Penalty(
        val value: Int,
        val summary: String,
    )

    /**
     * Severity → penalty points.
     */
    private fun severityPoints(s: Severity): Int =
        when (s) {
            Severity.CRITICAL -> 10
            Severity.HIGH -> 6
            Severity.MEDIUM -> 3
            Severity.LOW -> 1
            Severity.INFO, Severity.NONE -> 0
        }

    private fun penaltyFromFindings(
        findings: List<FindingLike>,
        sizeFactor: Double,
        category: String,
    ): Penalty {
        var sum = 0
        var critical = 0
        var high = 0
        var medium = 0
        var low = 0

        for (f in findings) {
            val pts = severityPoints(f.severity)
            if (pts == 0) continue

            sum += pts
            when (f.severity) {
                Severity.CRITICAL -> critical++
                Severity.HIGH -> high++
                Severity.MEDIUM -> medium++
                Severity.LOW -> low++
                Severity.INFO, Severity.NONE -> { }
            }
        }

        val penalty = (sum / sizeFactor).roundToInt()
        val countAny = critical + high + medium + low

        val summary =
            if (countAny == 0) {
                "No penalties (no CRITICAL/HIGH/MEDIUM/LOW findings in $category)."
            } else {
                "−$penalty (CRITICAL=$critical, HIGH=$high, MEDIUM=$medium, LOW=$low), " +
                    "rawPoints=$sum, sizeFactor=${"%.2f".format(sizeFactor)}."
            }

        return Penalty(penalty, summary)
    }

    private fun penaltyFromHotspots(
        h: HotspotsSnapshot,
        classCount: Int,
    ): Penalty {
        // penalize only if above thresholds, score can be 100
        val godThreshold = 120
        val fanOutThreshold = 300
        val depthThreshold = 8

        val topGod = h.topGodScores.take(10)
        val topFan = h.topFanOut.take(10)
        val topDepth = h.topDepth.take(10)

        val godExcessSum = topGod.sumOf { max(0, it - godThreshold) }
        val fanExcessSum = topFan.sumOf { max(0, it - fanOutThreshold) }
        val depthExcessSum = topDepth.sumOf { max(0, it - depthThreshold) }

        val sizeFactor = normalizeSizeFactor(classCount)

        // scale so one giant class doesn't nuke score + big repos not doomed.
        val godPenalty = ((godExcessSum / 200.0) * (25.0 / (sizeFactor / 10.0))).roundToInt()
        val fanPenalty = ((fanExcessSum / 800.0) * (25.0 / (sizeFactor / 10.0))).roundToInt()
        val depthPenalty = ((depthExcessSum / 20.0) * (20.0 / (sizeFactor / 10.0))).roundToInt()

        val total = clamp100(godPenalty + fanPenalty + depthPenalty)

        val summary =
            if (total == 0) {
                "No penalties (no hotspots above thresholds)."
            } else {
                "−$total (godExcess=$godExcessSum, fanExcess=$fanExcessSum, depthExcess=$depthExcessSum), " +
                    "sizeFactor=${"%.2f".format(sizeFactor)}."
            }

        return Penalty(total, summary)
    }

    /**
     * Category rules:
     * - Prefer id classification
     * - Title fallback is allowed, but only for unknown custom ids.
     * - avoid double counting.
     */
    private fun isStructural(f: FindingLike): Boolean {
        val id = f.id.uppercase()
        return when {
            // cyclic architecture deps
            id.contains("CYCLE") -> true
            id.contains("FORBIDDEN") -> true

            id.contains("LAYER_") && (id.contains("DEPENDS") || id.contains("FORBIDDEN")) -> true

            else -> titleFallback(f.title, listOf("Cycle", "Forbidden"))
        }
    }

    private fun isLayering(f: FindingLike): Boolean {
        val id = f.id.uppercase()
        return when {
            // style drift / role issues / layer mismatch warnings
            id.contains("STYLE_DRIFT") -> true
            id.contains("ROLE") -> true
            id.contains("LAYER") -> true

            else -> titleFallback(f.title, listOf("Layer", "Drift", "Role"))
        }
    }

    private fun isCoupling(f: FindingLike): Boolean {
        val id = f.id.uppercase()
        return when {
            id.contains("HIGH_FANOUT") -> true
            id.contains("GOD_CLASS") -> true
            id.contains("COUPLING") -> true
            id.contains("DEPENDENCY") -> true
            id.contains("FAN") -> true

            else -> titleFallback(f.title, listOf("Dependency", "Coupling", "Fan", "God class"))
        }
    }

    private fun titleFallback(
        title: String,
        keywords: List<String>,
    ): Boolean = keywords.any { kw -> title.contains(kw, ignoreCase = true) }

    private fun normalizeSizeFactor(classCount: Int): Double = max(10.0, sqrt(classCount.toDouble().coerceAtLeast(0.0)))

    private fun clamp100(x: Int): Int = min(100, max(0, x))
}
