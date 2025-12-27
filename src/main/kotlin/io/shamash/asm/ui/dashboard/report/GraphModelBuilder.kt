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

import io.shamash.asm.model.AsmClassInfo
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.model.AsmOrigin
import io.shamash.asm.model.Severity
import java.time.Instant

/**
 * Pure deterministic builder:
 * AsmIndex + Findings -> stable graph json model
 *
 * Schema:
 * shamash.graph.v1
 */
object GraphModelBuilder {
    data class GraphJson(
        val schema: String = "shamash.graph.v1",
        val meta: Meta,
        val score: Score,
        val nodes: List<Node>,
        val edges: List<Edge>,
    )

    data class Meta(
        val project: String,
        val generatedAt: Long,
        val nodeCount: Int,
        val edgeCount: Int,
    )

    data class Score(
        val overall: Int,
        val structural: Int,
        val coupling: Int,
        val complexity: Int,
        val layering: Int,
        val explain: List<ScoreExplainItem>,
    )

    data class ScoreExplainItem(
        val category: String,
        val penalty: Int,
        val summary: String,
    )

    data class Node(
        val id: String, // internal name
        val fqcn: String,
        val origin: String,
        val module: String?,
        val layer: String?,
        val fanIn: Int,
        val fanOut: Int,
        val depth: Int,
        val godScore: Int,
        val sevMax: String,
        val findingCount: Int,
    )

    data class Edge(
        val id: String,
        val source: String,
        val target: String,
        val kind: String,
        val weight: Int,
    )

    fun build(
        projectName: String,
        index: AsmIndex,
        allFindings: List<FindingLike>,
        score: ShamashScoreCalculator.ScoreResult,
        projectOnly: Boolean,
        hideJdk: Boolean,
    ): GraphJson {
        val classes = index.classes
        val refs = index.references

        val findingCountByInternal = HashMap<String, Int>(classes.size)
        val maxSevByInternal = HashMap<String, Severity>(classes.size)

        for (f in allFindings) {
            val fq = f.fqcn ?: continue
            val internal = fq.replace('.', '/')
            findingCountByInternal[internal] = (findingCountByInternal[internal] ?: 0) + 1

            val sev = f.severity
            val cur = maxSevByInternal[internal]
            if (cur == null || sev.rank < cur.rank) {
                maxSevByInternal[internal] = sev
            }
        }

        fun originToString(o: AsmOrigin): String =
            when (o) {
                AsmOrigin.MODULE_OUTPUT -> "PROJECT"
                AsmOrigin.DEPENDENCY_JAR -> "LIB"
            }

        fun inferLayer(fqcn: String): String? {
            val simple = fqcn.substringAfterLast('.')
            val lower = fqcn.lowercase()
            return when {
                ".controller." in lower || simple.endsWith("Controller") -> "controller"
                ".service." in lower || simple.endsWith("Service") -> "service"
                ".repository." in lower || simple.endsWith("Repository") -> "repository"
                ".dao." in lower || simple.endsWith("Dao") -> "dao"
                ".util." in lower || simple.endsWith("Util") -> "util"
                else -> null
            }
        }

        // compute fanIn among kept nodes
        val fanIn = HashMap<String, Int>(classes.size)
        val projectSet = classes.keys

        for ((from, tos) in refs) {
            val fromInfo = classes[from]
            if (fromInfo == null) continue

            if (projectOnly && fromInfo.origin != AsmOrigin.MODULE_OUTPUT) continue
            if (hideJdk && fromInfo.origin == AsmOrigin.DEPENDENCY_JAR && isJdkInternal(from)) continue

            for (to in tos) {
                val toInfo = classes[to] ?: continue
                if (projectOnly && toInfo.origin != AsmOrigin.MODULE_OUTPUT) continue
                if (hideJdk && toInfo.origin == AsmOrigin.DEPENDENCY_JAR && isJdkInternal(to)) continue

                // only count fanIn within projectSet?
                if (to !in projectSet) continue
                fanIn[to] = (fanIn[to] ?: 0) + 1
            }
        }

        // depth from class->super chain
        val depthMemo = HashMap<String, Int>(classes.size)

        fun depthOf(internal: String): Int {
            depthMemo[internal]?.let { return it }
            val sup =
                classes[internal]?.superInternalName ?: run {
                    depthMemo[internal] = 1
                    return 1
                }
            val d = if (sup in classes) 1 + depthOf(sup) else 2
            depthMemo[internal] = d
            return d
        }

        fun godScore(
            info: AsmClassInfo,
            fin: Int,
            fout: Int,
            depth: Int,
        ): Int {
            // mirrors hotspots “god” weights
            val m = info.methods.size
            val pm = info.methods.count { it.name != "<init>" && it.name != "<clinit>" && (it.access and 0x0001) != 0 }
            val f = info.fieldCount
            val ins = info.instructionCount / 50
            return (m * 3) + (pm * 2) + (f * 2) + ins + (fout * 2) + (fin * 2) + (depth - 1)
        }

        // keep nodes based on toggles
        val kept =
            classes.values
                .asSequence()
                .filter { info ->
                    if (projectOnly && info.origin != AsmOrigin.MODULE_OUTPUT) return@filter false
                    if (hideJdk && info.origin == AsmOrigin.DEPENDENCY_JAR && isJdkInternal(info.internalName)) return@filter false
                    true
                }.toList()

        val nodes =
            kept
                .map { info ->
                    val internal = info.internalName
                    val fin = fanIn[internal] ?: 0
                    val fout = info.referencedInternalNames.size
                    val d = depthOf(internal)
                    val g = godScore(info, fin, fout, d)

                    val maxSev = maxSevByInternal[internal] ?: Severity.NONE
                    val fc = findingCountByInternal[internal] ?: 0

                    Node(
                        id = internal,
                        fqcn = info.fqcn,
                        origin = originToString(info.origin),
                        module = info.moduleName,
                        layer = inferLayer(info.fqcn),
                        fanIn = fin,
                        fanOut = fout,
                        depth = d,
                        godScore = g,
                        sevMax = maxSev.label,
                        findingCount = fc,
                    )
                }.sortedBy { it.fqcn }

        // edges: from AsmIndex.references restricted to kept nodes
        val keptSet = nodes.asSequence().map { it.id }.toHashSet()

        val edges = ArrayList<Edge>(refs.size)
        for ((from, tos) in refs) {
            if (from !in keptSet) continue
            val counts = HashMap<String, Int>()
            for (to in tos) {
                if (to !in keptSet) continue
                counts[to] = (counts[to] ?: 0) + 1
            }
            for ((to, w) in counts) {
                edges.add(
                    Edge(
                        id = "$from->$to",
                        source = from,
                        target = to,
                        kind = "REF",
                        weight = w,
                    ),
                )
            }
        }
        edges.sortWith(compareBy<Edge>({ it.source }, { it.target }, { it.id }))

        val explain =
            score.explain.map {
                ScoreExplainItem(
                    category = it.category,
                    penalty = it.penalty,
                    summary = it.summary,
                )
            }

        val graphScore =
            Score(
                overall = score.overall,
                structural = score.structural,
                coupling = score.coupling,
                complexity = score.complexity,
                layering = score.layering,
                explain = explain,
            )

        val meta =
            Meta(
                project = projectName,
                generatedAt = Instant.now().epochSecond,
                nodeCount = nodes.size,
                edgeCount = edges.size,
            )

        return GraphJson(
            meta = meta,
            score = graphScore,
            nodes = nodes,
            edges = edges,
        )
    }

    /**
     * Minimal interface so we can build from your stable Finding model without importing tab classes.
     */
    interface FindingLike {
        val fqcn: String?
        val severity: Severity
    }

    fun toJson(graph: GraphJson): String {
        fun jsonStr(s: String): String =
            "\"" +
                s
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t") + "\""

        fun jsonNullableStr(s: String?): String = if (s == null) "null" else jsonStr(s)

        return buildString {
            append("{")
            append("\"schema\":").append(jsonStr(graph.schema)).append(',')

            append("\"meta\":{")
            append("\"project\":").append(jsonStr(graph.meta.project)).append(',')
            append("\"generatedAt\":").append(graph.meta.generatedAt).append(',')
            append("\"nodeCount\":").append(graph.meta.nodeCount).append(',')
            append("\"edgeCount\":").append(graph.meta.edgeCount)
            append("},")

            append("\"score\":{")
            append("\"overall\":").append(graph.score.overall).append(',')
            append("\"structural\":").append(graph.score.structural).append(',')
            append("\"coupling\":").append(graph.score.coupling).append(',')
            append("\"complexity\":").append(graph.score.complexity).append(',')
            append("\"layering\":").append(graph.score.layering).append(',')

            append("\"explain\":[")
            for (i in graph.score.explain.indices) {
                if (i > 0) append(',')
                val e = graph.score.explain[i]
                append("{")
                append("\"category\":").append(jsonStr(e.category)).append(',')
                append("\"penalty\":").append(e.penalty).append(',')
                append("\"summary\":").append(jsonStr(e.summary))
                append("}")
            }
            append("]")

            append("},")

            append("\"nodes\":[")
            for (i in graph.nodes.indices) {
                if (i > 0) append(',')
                val n = graph.nodes[i]
                append("{")
                append("\"id\":").append(jsonStr(n.id)).append(',')
                append("\"fqcn\":").append(jsonStr(n.fqcn)).append(',')
                append("\"origin\":").append(jsonStr(n.origin)).append(',')
                append("\"module\":").append(jsonNullableStr(n.module)).append(',')
                append("\"layer\":").append(jsonNullableStr(n.layer)).append(',')
                append("\"fanIn\":").append(n.fanIn).append(',')
                append("\"fanOut\":").append(n.fanOut).append(',')
                append("\"depth\":").append(n.depth).append(',')
                append("\"godScore\":").append(n.godScore).append(',')
                append("\"sevMax\":").append(jsonStr(n.sevMax)).append(',')
                append("\"findingCount\":").append(n.findingCount)
                append("}")
            }
            append("],")

            append("\"edges\":[")
            for (i in graph.edges.indices) {
                if (i > 0) append(',')
                val e = graph.edges[i]
                append("{")
                append("\"id\":").append(jsonStr(e.id)).append(',')
                append("\"source\":").append(jsonStr(e.source)).append(',')
                append("\"target\":").append(jsonStr(e.target)).append(',')
                append("\"kind\":").append(jsonStr(e.kind)).append(',')
                append("\"weight\":").append(e.weight)
                append("}")
            }
            append("]")

            append("}")
        }
    }

    private fun isJdkInternal(internal: String): Boolean =
        internal.startsWith("java/") ||
            internal.startsWith("javax/") ||
            internal.startsWith("jdk/") ||
            internal.startsWith("sun/")
}
