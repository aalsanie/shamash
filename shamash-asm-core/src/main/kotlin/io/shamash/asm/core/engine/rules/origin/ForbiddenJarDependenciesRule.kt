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
package io.shamash.asm.core.engine.rules.origin

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.artifacts.util.PathNormalizer
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.query.FactIndex
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.regex.Pattern

/**
 * origin.forbiddenJarDependencies
 *
 * Params:
 * - forbid (required, non-empty list<object>)
 *     Each object:
 *       - from (required string): regex matched against depender origin (jar path OR jar file name)
 *       - to   (required string): regex matched against dependency origin (jar path OR jar file name)
 *
 * Matching semantics (per your instruction):
 * - "forbid.from" matches if it matches EITHER:
 *     - normalized jar path (e.g., /home/me/.m2/.../foo-1.2.3.jar)
 *     - jar file name (e.g., foo-1.2.3.jar)
 * - same for "forbid.to"
 *
 * Enforcement semantics:
 * - Uses facts.edges and resolves each endpoint TypeRef to its ClassFact (if present) to infer origin.
 * - Applies rule scope filtering to the *from-class* (package/glob) and role filters to *fromRole*.
 * - Emits one finding per (forbid[i], fromJar, toJar) with examples (truncated).
 */
class ForbiddenJarDependenciesRule : Rule {
    override val id: String = "origin.forbiddenJarDependencies"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val forbids = readForbids(rule) ?: return emptyList()
        if (forbids.isEmpty() || facts.edges.isEmpty() || facts.classes.isEmpty()) return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)
        val classByFqn: Map<String, ClassFact> = facts.classes.associateBy { it.fqName }

        // Deterministic edge iteration.
        val edges =
            facts.edges.sortedWith(
                compareBy<DependencyEdge>({ it.from.fqName }, { it.to.fqName }, { it.kind.name }, { it.detail ?: "" }),
            )

        data class Key(
            val forbidIndex: Int,
            val fromOriginKey: String,
            val toOriginKey: String,
        )

        data class Bucket(
            val forbid: ForbidPair,
            val from: OriginId,
            val to: OriginId,
            val examples: LinkedHashSet<String>,
            var anchorEdge: DependencyEdge? = null,
        )

        val buckets = LinkedHashMap<Key, Bucket>()

        for (e in edges) {
            val fromClass = classByFqn[e.from.fqName] ?: continue
            val toClass = classByFqn[e.to.fqName] ?: continue

            // Apply scope on from-class and role filters on fromRole.
            val fromRole = facts.classToRole[fromClass.fqName]
            if (!RuleUtil.roleAllowed(rule, scope, fromRole)) continue
            if (!RuleUtil.classInScope(fromClass, scope)) continue

            val fromOrigin = originId(fromClass.location)
            val toOrigin = originId(toClass.location)

            // This rule is about origins; if either side can't be identified, skip.
            if (fromOrigin.key.isEmpty() || toOrigin.key.isEmpty()) continue

            forbids.forEachIndexed { idx, fp ->
                if (!fp.matchesFrom(fromOrigin)) return@forEachIndexed
                if (!fp.matchesTo(toOrigin)) return@forEachIndexed

                val k = Key(idx, fromOrigin.key, toOrigin.key)
                val bucket =
                    buckets.getOrPut(k) {
                        Bucket(
                            forbid = fp,
                            from = fromOrigin,
                            to = toOrigin,
                            examples = LinkedHashSet(),
                            anchorEdge = e,
                        )
                    }

                if (bucket.anchorEdge == null) bucket.anchorEdge = e

                // Deterministic compact example format; keep bounded later.
                if (bucket.examples.size < EXAMPLES_LIMIT) {
                    bucket.examples += "${e.from.fqName} -> ${e.to.fqName}"
                }
            }
        }

        if (buckets.isEmpty()) return emptyList()

        // Deterministic ordering of findings.
        val out = ArrayList<Finding>(buckets.size)
        val ordered =
            buckets.entries.sortedWith(
                compareBy<Map.Entry<Key, Bucket>>(
                    { it.value.from.name },
                    { it.value.to.name },
                    { it.value.forbid.fromRaw },
                    { it.value.forbid.toRaw },
                ),
            )

        for ((_, b) in ordered) {
            val anchor = b.anchorEdge
            val filePath = if (anchor != null) RuleUtil.filePathOf(anchor.location) else ""

            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message =
                        buildString {
                            append("Forbidden origin dependency detected: ")
                            append("'${b.from.name}' -> '${b.to.name}' ")
                            append("(matched forbid.from='${b.forbid.fromRaw}', forbid.to='${b.forbid.toRaw}').")
                        },
                    filePath = filePath,
                    severity = rule.severity,
                    classFqn = anchor?.from?.fqName,
                    memberName = null,
                    data =
                        buildMap {
                            put("forbidFrom", b.forbid.fromRaw)
                            put("forbidTo", b.forbid.toRaw)

                            put("fromOriginKind", b.from.kind.name)
                            put("fromOriginPath", b.from.path)
                            put("fromOriginName", b.from.name)

                            put("toOriginKind", b.to.kind.name)
                            put("toOriginPath", b.to.path)
                            put("toOriginName", b.to.name)

                            if (b.examples.isNotEmpty()) {
                                put("examples", b.examples.joinToString(","))
                                if (b.examples.size >= EXAMPLES_LIMIT) put("examplesTruncated", "true")
                            }
                        },
                )
        }

        return out
    }

    private data class ForbidPair(
        val fromRaw: String,
        val toRaw: String,
        val from: Pattern,
        val to: Pattern,
    ) {
        fun matchesFrom(origin: OriginId): Boolean = from.matcher(origin.path).find() || from.matcher(origin.name).find()

        fun matchesTo(origin: OriginId): Boolean = to.matcher(origin.path).find() || to.matcher(origin.name).find()
    }

    private data class OriginId(
        val kind: OriginKind,
        val path: String,
        val name: String,
    ) {
        /**
         * Stable key for grouping:
         * - jar => normalized jar path
         * - dir => normalized origin path (class file path)
         */
        val key: String get() = path
    }

    private fun originId(loc: SourceLocation): OriginId {
        val n = loc.normalized()
        val path =
            when (n.originKind) {
                OriginKind.JAR_ENTRY -> PathNormalizer.normalize(n.containerPath ?: n.originPath)
                OriginKind.DIR_CLASS -> PathNormalizer.normalize(n.originPath)
            }
        val name = fileNameOf(path)
        return OriginId(kind = n.originKind, path = path, name = name)
    }

    private fun fileNameOf(path: String): String {
        if (path.isBlank()) return ""
        val s = path.replace('\\', '/')
        return s.substringAfterLast('/', s)
    }

    private fun readForbids(rule: RuleDef): List<ForbidPair>? {
        val paramsPath = "rules.${rule.type}.${rule.name}.params"
        val p = Params.of(rule.params, path = paramsPath)

        val raw: Any = rule.params["forbid"] ?: return null
        val list = raw as? List<*> ?: return null
        if (list.isEmpty()) return null

        val out = ArrayList<ForbidPair>(list.size)

        try {
            list.forEachIndexed { i, item ->
                val at = "$paramsPath.forbid[$i]"
                val obj = item as? Map<*, *> ?: throw ParamError(at, "must be an object/map")

                val map = LinkedHashMap<String, Any?>(obj.size)
                for ((k, v) in obj) {
                    if (k == null) throw ParamError(at, "map key must not be null")
                    map[k.toString()] = v
                }

                val itemParams = Params.of(map, path = at)
                val fromRaw = itemParams.requireString("from").trim()
                val toRaw = itemParams.requireString("to").trim()

                if (fromRaw.isEmpty()) throw ParamError("$at.from", "must be non-empty")
                if (toRaw.isEmpty()) throw ParamError("$at.to", "must be non-empty")

                out +=
                    ForbidPair(
                        fromRaw = fromRaw,
                        toRaw = toRaw,
                        from = Pattern.compile(fromRaw),
                        to = Pattern.compile(toRaw),
                    )
            }
        } catch (_: ParamError) {
            // validator should catch; engine stays resilient
            return null
        } catch (_: Throwable) {
            return null
        }

        // Deterministic de-dupe (by raw strings).
        return out.distinctBy { it.fromRaw to it.toRaw }
    }

    private companion object {
        private const val EXAMPLES_LIMIT = 20
    }
}
