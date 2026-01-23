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
package io.shamash.asm.core.analysis

import io.shamash.asm.core.config.schema.v1.model.AnalysisConfig
import io.shamash.asm.core.config.schema.v1.model.GodClassWeights
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.OverallWeights
import io.shamash.asm.core.config.schema.v1.model.ScoreModel
import io.shamash.asm.core.config.schema.v1.model.ScoreThresholds
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.MethodRef
import io.shamash.asm.core.facts.query.FactIndex
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Analysis pipeline:
 * - compute graph snapshots + SCC/cycle summaries
 * - compute hotspots (topN) by a small set of metrics (v1)
 * - compute scoring outputs (ScoreModel.V1)
 */
object AsmAnalysisPipeline {
    fun shouldRun(analysis: AnalysisConfig): Boolean {
        if (analysis.graphs.enabled) return true
        if (analysis.hotspots.enabled) return true
        if (analysis.scoring.enabled) return true
        return false
    }

    fun run(
        facts: FactIndex,
        analysis: AnalysisConfig,
    ): AnalysisResult {
        if (!shouldRun(analysis)) return AnalysisResult()

        val graphs = if (analysis.graphs.enabled) computeGraphs(facts, analysis) else null
        val hotspots = if (analysis.hotspots.enabled) computeHotspots(facts, analysis) else null
        val scoring = if (analysis.scoring.enabled) computeScoring(facts, analysis) else null

        return AnalysisResult(graphs = graphs, hotspots = hotspots, scoring = scoring)
    }

    private fun computeGraphs(
        facts: FactIndex,
        analysis: AnalysisConfig,
    ): GraphAnalysisResult {
        val cfg = analysis.graphs

        val g = RuleUtil.buildDependencyGraph(facts, cfg.granularity, cfg.includeExternalBuckets)

        val nodes = g.nodes.toList().sorted()
        val adjacency: Map<String, List<String>> =
            nodes.associateWith { n -> g.successors(n).toList().sorted() }

        val sccs = RuleUtil.stronglyConnectedComponents(g)
        val cyclic =
            RuleUtil
                .cyclicComponents(g)
                .map { it.toList().sorted() }
                .sortedBy { it.firstOrNull().orEmpty() }

        // Representative cycles (bounded) for UI/CI summaries.
        val repCyclesRaw =
            RuleUtil.representativeCyclesBounded(
                nodes = nodes,
                outgoing = { n -> g.successors(n).toList().sorted() },
                maxCycles = 50,
                maxCycleNodes = 120,
            )
        val repCycles = repCyclesRaw.map { it.map { x -> x.toString() } }

        return GraphAnalysisResult(
            granularity = cfg.granularity,
            includeExternalBuckets = cfg.includeExternalBuckets,
            nodes = nodes,
            adjacency = adjacency,
            nodeCount = nodes.size,
            edgeCount = g.edgeCount,
            sccCount = sccs.size,
            cyclicSccs = cyclic,
            representativeCycles = repCycles,
        )
    }

    private fun computeHotspots(
        facts: FactIndex,
        analysis: AnalysisConfig,
    ): HotspotsResult {
        val cfg = analysis.hotspots

        val classGraph = RuleUtil.buildDependencyGraph(facts, Granularity.CLASS, cfg.includeExternal)
        val packageGraph = RuleUtil.buildDependencyGraph(facts, Granularity.PACKAGE, cfg.includeExternal)

        val classFanOut = RuleUtil.fanOut(classGraph)
        val classFanIn = RuleUtil.fanIn(classGraph)

        val pkgFanOut = RuleUtil.fanOut(packageGraph)
        val pkgFanIn = RuleUtil.fanIn(packageGraph)

        val methodsByOwner: Map<String, List<MethodRef>> =
            facts.methods
                .asSequence()
                .sortedWith(compareBy<MethodRef>({ it.owner.fqName }, { it.name }, { it.desc }))
                .groupBy { it.owner.fqName }

        val methodCountByClass =
            facts.classes
                .asSequence()
                .map { c -> c.fqName to methodsByOwner[c.fqName].orEmpty().size }
                .toMap()

        val methodCountByPackage = LinkedHashMap<String, Int>()
        for (c in facts.classes.sortedBy { it.fqName }) {
            val pkg = c.packageName
            val cnt = methodCountByClass[c.fqName] ?: 0
            methodCountByPackage[pkg] = (methodCountByPackage[pkg] ?: 0) + cnt
        }

        val classPkgSpread = computeClassPackageSpread(facts, cfg.includeExternal)
        val pkgSpread = computePackageSpread(facts, cfg.includeExternal)

        val classHotspots =
            buildHotspots(
                kind = HotspotKind.CLASS,
                topN = cfg.topN,
                metricMaps =
                    mapOf(
                        HotspotMetric.FAN_IN to classFanIn,
                        HotspotMetric.FAN_OUT to classFanOut,
                        HotspotMetric.PACKAGE_SPREAD to classPkgSpread,
                        HotspotMetric.METHOD_COUNT to methodCountByClass,
                    ),
            )

        val packageHotspots =
            buildHotspots(
                kind = HotspotKind.PACKAGE,
                topN = cfg.topN,
                metricMaps =
                    mapOf(
                        HotspotMetric.FAN_IN to pkgFanIn,
                        HotspotMetric.FAN_OUT to pkgFanOut,
                        HotspotMetric.PACKAGE_SPREAD to pkgSpread,
                        HotspotMetric.METHOD_COUNT to methodCountByPackage,
                    ),
            )

        return HotspotsResult(
            topN = cfg.topN,
            includeExternal = cfg.includeExternal,
            classHotspots = classHotspots,
            packageHotspots = packageHotspots,
        )
    }

    private fun computeScoring(
        facts: FactIndex,
        analysis: AnalysisConfig,
    ): ScoringResult {
        val cfg = analysis.scoring
        if (cfg.model != ScoreModel.V1) {
            // Stage 1 only supports V1 (future models can be plugged in without breaking exports).
            return ScoringResult(model = cfg.model)
        }

        val god = if (cfg.godClass.enabled) scoreGodClasses(facts, analysis) else null
        val overall = if (cfg.overall.enabled) scorePackages(facts, analysis, god) else null

        return ScoringResult(model = cfg.model, godClass = god, overall = overall)
    }

    private fun scoreGodClasses(
        facts: FactIndex,
        analysis: AnalysisConfig,
    ): GodClassScoringResult {
        val cfg = analysis.scoring.godClass

        val weights = cfg.weights ?: DEFAULT_GOD_WEIGHTS
        val thresholds = cfg.thresholds?.let { ScoreThresholds(it.warning, it.error) } ?: DEFAULT_THRESHOLDS

        val classGraph = RuleUtil.buildDependencyGraph(facts, Granularity.CLASS, includeExternalBuckets = false)
        val fanOut = RuleUtil.fanOut(classGraph)
        val fanIn = RuleUtil.fanIn(classGraph)

        val methodsByOwner: Map<String, List<MethodRef>> =
            facts.methods
                .asSequence()
                .sortedWith(compareBy<MethodRef>({ it.owner.fqName }, { it.name }, { it.desc }))
                .groupBy { it.owner.fqName }

        val fieldsByOwner: Map<String, Int> =
            facts.fields
                .asSequence()
                .sortedWith(compareBy({ it.owner.fqName }, { it.name }, { it.desc }))
                .groupingBy { it.owner.fqName }
                .eachCount()

        val methodCount = facts.classes.associate { c -> c.fqName to methodsByOwner[c.fqName].orEmpty().size }
        val fieldCount = facts.classes.associate { c -> c.fqName to (fieldsByOwner[c.fqName] ?: 0) }
        val pkgSpread = computeClassPackageSpread(facts, includeExternal = false)

        val maxMethods = methodCount.values.maxOrNull()?.coerceAtLeast(0) ?: 0
        val maxFields = fieldCount.values.maxOrNull()?.coerceAtLeast(0) ?: 0
        val maxFanOut = fanOut.values.maxOrNull()?.coerceAtLeast(0) ?: 0
        val maxFanIn = fanIn.values.maxOrNull()?.coerceAtLeast(0) ?: 0
        val maxSpread = pkgSpread.values.maxOrNull()?.coerceAtLeast(0) ?: 0

        fun normInt(
            v: Int,
            max: Int,
        ): Double = if (max <= 0) 0.0 else (v.toDouble() / max.toDouble()).coerceIn(0.0, 1.0)

        val rows =
            facts.classes
                .asSequence()
                .sortedBy { it.fqName }
                .map { c ->
                    val id = c.fqName
                    val raw =
                        linkedMapOf(
                            "methods" to (methodCount[id] ?: 0),
                            "fields" to (fieldCount[id] ?: 0),
                            "fanOut" to (fanOut[id] ?: 0),
                            "fanIn" to (fanIn[id] ?: 0),
                            "packageSpread" to (pkgSpread[id] ?: 0),
                        )

                    val normalized =
                        linkedMapOf(
                            "methods" to normInt(raw.getValue("methods"), maxMethods),
                            "fields" to normInt(raw.getValue("fields"), maxFields),
                            "fanOut" to normInt(raw.getValue("fanOut"), maxFanOut),
                            "fanIn" to normInt(raw.getValue("fanIn"), maxFanIn),
                            "packageSpread" to normInt(raw.getValue("packageSpread"), maxSpread),
                        )

                    val score =
                        weightedAverage(
                            listOf(
                                weights.methods to normalized.getValue("methods"),
                                weights.fields to normalized.getValue("fields"),
                                weights.fanOut to normalized.getValue("fanOut"),
                                weights.fanIn to normalized.getValue("fanIn"),
                                weights.packageSpread to normalized.getValue("packageSpread"),
                            ),
                        )

                    ClassScoreRow(
                        classFqn = id,
                        packageName = c.packageName,
                        score = score,
                        band = band(score, thresholds),
                        raw = raw,
                        normalized = normalized,
                    )
                }.sortedWith(compareByDescending<ClassScoreRow> { it.score }.thenBy { it.classFqn })
                .toList()

        return GodClassScoringResult(thresholds = thresholds, rows = rows)
    }

    private fun scorePackages(
        facts: FactIndex,
        analysis: AnalysisConfig,
        god: GodClassScoringResult?,
    ): OverallScoringResult {
        val cfg = analysis.scoring.overall

        val weights = cfg.weights ?: DEFAULT_OVERALL_WEIGHTS
        val thresholds = cfg.thresholds?.let { ScoreThresholds(it.warning, it.error) } ?: DEFAULT_THRESHOLDS

        val projectPkgs =
            facts.classes
                .asSequence()
                .map { it.packageName }
                .toSet()

        // Graph for cycles/degree (project-only)
        val pkgGraphProject = RuleUtil.buildDependencyGraph(facts, Granularity.PACKAGE, includeExternalBuckets = false)
        val nodesProject = pkgGraphProject.nodes.filter { it in projectPkgs }.toSet()
        val n = nodesProject.size
        val denom = if (n <= 1) 1.0 else (n - 1).toDouble()

        val cyc = RuleUtil.cyclicComponents(pkgGraphProject)
        val inCycle = HashSet<String>()
        for (comp in cyc) inCycle.addAll(comp)

        // Graph for external coupling (with buckets)
        val pkgGraphExternal = RuleUtil.buildDependencyGraph(facts, Granularity.PACKAGE, includeExternalBuckets = true)

        // God class prevalence: mean of god-class score by package.
        val godScoreByClass = god?.rows?.associate { it.classFqn to it.score }.orEmpty()
        val godAvgByPackage = LinkedHashMap<String, Double>()
        val godCntByPackage = LinkedHashMap<String, Int>()
        for (c in facts.classes.sortedBy { it.fqName }) {
            val pkg = c.packageName
            val s = godScoreByClass[c.fqName] ?: 0.0
            godAvgByPackage[pkg] = (godAvgByPackage[pkg] ?: 0.0) + s
            godCntByPackage[pkg] = (godCntByPackage[pkg] ?: 0) + 1
        }
        for ((pkg, sum) in godAvgByPackage.toMap()) {
            val cnt = godCntByPackage[pkg] ?: 1
            godAvgByPackage[pkg] = (sum / cnt.toDouble()).coerceIn(0.0, 1.0)
        }

        // Dependency density: use local out-degree density as a per-package proxy.
        val fanOutProject = RuleUtil.fanOut(pkgGraphProject)

        val rows =
            projectPkgs
                .toList()
                .sorted()
                .map { pkg ->
                    val cycles = if (pkg in inCycle) 1.0 else 0.0
                    val out = (fanOutProject[pkg] ?: 0).toDouble()
                    val depDensity = (out / denom).coerceIn(0.0, 1.0)
                    val layering = 0.0 // not implemented in Stage 1 (no layering rule yet)
                    val godPrev = (godAvgByPackage[pkg] ?: 0.0).coerceIn(0.0, 1.0)

                    val succAll = pkgGraphExternal.successors(pkg)
                    val totalOut = succAll.size.toDouble()
                    val externalOut = succAll.count { it.startsWith(RuleUtil.EXTERNAL_PREFIX) }.toDouble()
                    val externalCoupling = if (totalOut <= 0.0) 0.0 else (externalOut / totalOut).coerceIn(0.0, 1.0)

                    val raw =
                        linkedMapOf(
                            "cycles" to cycles,
                            "dependencyDensity" to depDensity,
                            "layeringViolations" to layering,
                            "godClassPrevalence" to godPrev,
                            "externalCoupling" to externalCoupling,
                        )

                    // Normalized is currently identical for all components (0..1).
                    val normalized = raw.toMap()

                    val score =
                        weightedAverage(
                            listOf(
                                weights.cycles to cycles,
                                weights.dependencyDensity to depDensity,
                                weights.layeringViolations to layering,
                                weights.godClassPrevalence to godPrev,
                                weights.externalCoupling to externalCoupling,
                            ),
                        )

                    PackageScoreRow(
                        packageName = pkg,
                        score = score,
                        band = band(score, thresholds),
                        raw = raw,
                        normalized = normalized,
                    )
                }.sortedWith(compareByDescending<PackageScoreRow> { it.score }.thenBy { it.packageName })
                .toList()

        return OverallScoringResult(thresholds = thresholds, rows = rows)
    }

    private fun buildHotspots(
        kind: HotspotKind,
        topN: Int,
        metricMaps: Map<HotspotMetric, Map<String, Int>>,
    ): List<HotspotEntry> {
        val acc = LinkedHashMap<String, LinkedHashMap<HotspotMetric, HotspotReason>>()

        for ((metric, m) in metricMaps) {
            val top =
                m
                    .asSequence()
                    .filter { (_, v) -> v > 0 }
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(topN)
                    .toList()

            top.forEachIndexed { idx, e ->
                val id = e.key
                val reasons = acc.getOrPut(id) { LinkedHashMap() }
                reasons[metric] = HotspotReason(metric = metric, value = e.value, rank = idx + 1)
            }
        }

        return acc
            .map { (id, rs) ->
                val reasons = rs.values.toList().sortedBy { it.metric.name }
                HotspotEntry(kind = kind, id = id, reasons = reasons)
            }.sortedWith(compareByDescending<HotspotEntry> { it.maxMetricValue }.thenBy { it.id })
            .toList()
    }

    private fun computeClassPackageSpread(
        facts: FactIndex,
        includeExternal: Boolean,
    ): Map<String, Int> {
        if (facts.edges.isEmpty() || facts.classes.isEmpty()) return emptyMap()

        val projectPackages: Set<String> =
            facts.classes
                .asSequence()
                .map { it.packageName }
                .toSet()
        val classPkg = facts.classes.associate { it.fqName to it.packageName }

        val spread = LinkedHashMap<String, LinkedHashSet<String>>()
        val edges = stableEdges(facts.edges)

        for (e in edges) {
            val from = e.from.fqName
            if (from !in classPkg) continue

            val toPkg = classPkg[e.to.fqName] ?: e.to.packageName
            if (!includeExternal && toPkg !in projectPackages) continue

            spread.getOrPut(from) { LinkedHashSet() }.add(toPkg)
        }

        return spread.mapValues { (_, tos) -> tos.size }
    }

    private fun computePackageSpread(
        facts: FactIndex,
        includeExternal: Boolean,
    ): Map<String, Int> {
        if (facts.edges.isEmpty() || facts.classes.isEmpty()) return emptyMap()

        val projectPackages: Set<String> =
            facts.classes
                .asSequence()
                .map { it.packageName }
                .toSet()
        val classPkg = facts.classes.associate { it.fqName to it.packageName }

        val spread = LinkedHashMap<String, LinkedHashSet<String>>()
        val edges = stableEdges(facts.edges)

        for (e in edges) {
            val fromPkg = classPkg[e.from.fqName] ?: e.from.packageName
            val toPkg = classPkg[e.to.fqName] ?: e.to.packageName
            if (!includeExternal && toPkg !in projectPackages) continue
            spread.getOrPut(fromPkg) { LinkedHashSet() }.add(toPkg)
        }

        return spread.mapValues { (_, tos) -> tos.size }
    }

    private fun stableEdges(edges: List<DependencyEdge>): List<DependencyEdge> =
        edges.sortedWith(compareBy<DependencyEdge>({ it.from.fqName }, { it.to.fqName }, { it.kind.name }, { it.detail ?: "" }))

    private fun weightedAverage(terms: List<Pair<Double, Double>>): Double {
        var wSum = 0.0
        var sum = 0.0
        for ((w, v) in terms) {
            if (!w.isFinite() || w <= 0.0) continue
            val vv = if (v.isFinite()) v else 0.0
            wSum += w
            sum += (w * vv)
        }
        return if (wSum <= 0.0) 0.0 else (sum / wSum).coerceIn(0.0, 1.0)
    }

    private fun band(
        score: Double,
        t: ScoreThresholds,
    ): SeverityBand {
        if (!score.isFinite()) return SeverityBand.OK
        return when {
            score >= t.error -> SeverityBand.ERROR
            score >= t.warning -> SeverityBand.WARN
            else -> SeverityBand.OK
        }
    }

    // Defaults (match shamash-asm.reference.yml)
    private val DEFAULT_THRESHOLDS = ScoreThresholds(warning = 0.70, error = 0.85)
    private val DEFAULT_GOD_WEIGHTS =
        GodClassWeights(
            methods = 0.35,
            fields = 0.10,
            fanOut = 0.30,
            fanIn = 0.15,
            packageSpread = 0.10,
        )
    private val DEFAULT_OVERALL_WEIGHTS =
        OverallWeights(
            cycles = 0.30,
            dependencyDensity = 0.20,
            layeringViolations = 0.25,
            godClassPrevalence = 0.15,
            externalCoupling = 0.10,
        )
}
