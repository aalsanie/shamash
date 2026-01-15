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
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.query.FactIndex

/**
 * arch.allowOnlyRoot
 *
 * Params:
 * - root: string (required)  e.g. "com.acme"
 * - allowDefaultPackage: boolean (optional, default false)
 *
 * Semantics:
 * - For classes in scope (roles + scope filters), their package must:
 *   - equal root, OR
 *   - start with root + "."
 * - If allowDefaultPackage=false, default package ("") is forbidden.
 *
 * This is a stricter/simpler variant of AllowedPackagesRule when you want a single root.
 */
class AllowOnlyRootRule : Rule {
    override val id: String = "arch.allowOnlyRoot"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val params = readParams(rule) ?: return emptyList()
        val scope = RuleUtil.compileScope(rule.scope)

        val root = params.root
        val rootPrefix = "$root."

        val out = ArrayList<Finding>()

        val classes = facts.classes.sortedBy { it.fqName }
        for (c in classes) {
            val roleId = facts.classToRole[c.fqName]
            if (!RuleUtil.roleAllowed(rule, scope, roleId)) continue
            if (!RuleUtil.classInScope(c, scope)) continue

            val pkg = c.packageName // "" allowed (default package)
            val ok =
                when {
                    pkg.isEmpty() -> params.allowDefaultPackage
                    pkg == root -> true
                    pkg.startsWith(rootPrefix) -> true
                    else -> false
                }

            if (ok) continue

            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message =
                        buildString {
                            append("Class '")
                            append(c.fqName)
                            append("' is outside allowed root '")
                            append(root)
                            append("'. Found package '")
                            append(if (pkg.isEmpty()) "<default>" else pkg)
                            append("'.")
                        },
                    filePath = RuleUtil.filePathOf(c.location),
                    severity = rule.severity,
                    classFqn = c.fqName,
                    memberName = null,
                    data =
                        mapOf(
                            "root" to root,
                            "packageName" to pkg,
                            "allowDefaultPackage" to params.allowDefaultPackage.toString(),
                            "roleId" to (roleId ?: ""),
                            "classInternalName" to c.type.internalName,
                        ),
                )
        }

        return out
    }

    private data class ReadParams(
        val root: String,
        val allowDefaultPackage: Boolean,
    )

    private fun readParams(rule: RuleDef): ReadParams? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")

        val root =
            try {
                p.requireString("root").trim()
            } catch (_: ParamError) {
                return null
            }
        if (root.isEmpty()) return null

        val allowDefaultPackage = runCatching { p.optionalBoolean("allowDefaultPackage") }.getOrNull() ?: false

        return ReadParams(
            root = root,
            allowDefaultPackage = allowDefaultPackage,
        )
    }
}
