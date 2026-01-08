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
package io.shamash.psi.engine.rules

import com.intellij.psi.PsiFile
import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.validation.v1.params.ParamError
import io.shamash.psi.config.validation.v1.params.Params
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.facts.model.v1.DependencyKind
import io.shamash.psi.facts.model.v1.FactsIndex
import java.util.LinkedHashMap

/**
 * Enforces forbidden role-to-role dependencies.
 *
 * Params (v1):
 *  - forbidden: required non-empty list of objects: [{from: "controller", to: ["service","repo"], message?: "..."}]
 *  - kinds (optional): list of dependency kinds to restrict matching.
 *
 * Notes:
 * - Engine treats config as validated, but still produces ParamError for corrupt/invalid runtime values.
 * - This rule relies on facts.classToRole being populated by the engine pipeline.
 */
class ArchForbiddenRoleDependenciesRule : EngineRule {
    override val id: String = "arch.forbiddenRoleDependencies"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val ruleInstanceId = RuleUtil.ruleInstanceId(rule, fallbackEngineRuleId = id)
        val p = Params.of(rule.params, "rules.${rule.type}.${rule.name}.params")

        // kinds: optional (validated upstream); still safe-map here.
        val kindsRaw = p.optionalStringList("kinds") ?: emptyList()
        val allowedKinds = parseKinds(kindsRaw)

        // forbidden: required list of maps
        val forbiddenRaw = rule.params["forbidden"] ?: throw ParamError("${p.currentPath}.forbidden", "is required")
        if (forbiddenRaw !is List<*>) throw ParamError("${p.currentPath}.forbidden", "must be a list")
        if (forbiddenRaw.isEmpty()) throw ParamError("${p.currentPath}.forbidden", "must be non-empty")

        // Build map: fromRole -> list of ForbiddenEntry(toRoles, message?)
        val forbMap = LinkedHashMap<String, List<ForbiddenEntry>>()

        forbiddenRaw.forEachIndexed { i, item ->
            val entryPath = "${p.currentPath}.forbidden[$i]"
            val m = item as? Map<*, *> ?: throw ParamError(entryPath, "must be an object/map")
            val norm = LinkedHashMap<String, Any?>(m.size)
            for ((k, v) in m) {
                if (k == null) throw ParamError(entryPath, "map key must not be null")
                norm[k.toString()] = v
            }
            val ep = Params.of(norm, entryPath)

            val from = ep.requireString("from").trim()
            val to = ep.requireStringList("to", nonEmpty = true).map { it.trim() }.filter { it.isNotEmpty() }
            val msg = ep.optionalString("message")?.trim()?.takeIf { it.isNotEmpty() }

            if (from.isEmpty()) throw ParamError("$entryPath.from", "must be non-empty")
            if (to.isEmpty()) throw ParamError("$entryPath.to", "must be non-empty")

            forbMap[from] = (forbMap[from].orEmpty() + ForbiddenEntry(to.toSet(), msg))
        }

        if (forbMap.isEmpty()) return emptyList()

        val sev = RuleUtil.severity(rule)
        val filePath = normalizePath(file.virtualFile?.path ?: file.name)

        val out = ArrayList<Finding>()
        for (d in facts.dependencies) {
            if (allowedKinds != null && d.kind !in allowedKinds) continue

            val fromRole = facts.classToRole[d.fromClassFqn] ?: continue
            val toRole = facts.classToRole[d.toTypeFqn] ?: continue // external types have no role -> ignore

            val entries = forbMap[fromRole] ?: continue
            val hit = entries.firstOrNull { toRole in it.toRoles } ?: continue

            val custom = hit.message
            val message =
                custom ?: (
                    "Forbidden dependency: role '$fromRole' (${d.fromClassFqn}) " +
                        "depends on role '$toRole' (${d.toTypeFqn}) " +
                        "via ${d.kind}${d.detail?.let { " ($it)" } ?: ""}."
                )

            out +=
                Finding(
                    ruleId = ruleInstanceId,
                    message = message,
                    filePath = filePath,
                    severity = sev,
                    classFqn = d.fromClassFqn,
                    data =
                        linkedMapOf(
                            "fromRole" to fromRole,
                            "toRole" to toRole,
                            "fromClassFqn" to d.fromClassFqn,
                            "toTypeFqn" to d.toTypeFqn,
                            "kind" to d.kind.name,
                            "detail" to (d.detail ?: ""),
                        ),
                )
        }

        return out
    }

    private data class ForbiddenEntry(
        val toRoles: Set<String>,
        val message: String?,
    )

    /**
     * Spec uses camelCase names (methodCall, fieldType, ...).
     * Facts use enum names (METHOD_CALL, FIELD_TYPE, ...).
     */
    private fun parseKinds(kindsRaw: List<String>): Set<DependencyKind>? {
        if (kindsRaw.isEmpty()) return null

        val allowed = LinkedHashSet<DependencyKind>()
        for (raw in kindsRaw) {
            val k = raw.trim()
            if (k.isEmpty()) continue
            val kind =
                when (k) {
                    "methodCall" -> DependencyKind.METHOD_CALL
                    "fieldType" -> DependencyKind.FIELD_TYPE
                    "parameterType" -> DependencyKind.PARAMETER_TYPE
                    "returnType" -> DependencyKind.RETURN_TYPE
                    "extends" -> DependencyKind.EXTENDS
                    "implements" -> DependencyKind.IMPLEMENTS
                    "annotationType" -> DependencyKind.ANNOTATION_TYPE
                    else -> null
                }
            if (kind != null) allowed += kind
        }
        return if (allowed.isEmpty()) null else allowed
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
