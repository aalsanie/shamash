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

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.validation.v1.params.ParamError
import io.shamash.psi.config.validation.v1.params.Params
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.facts.model.v1.FactsIndex
import java.util.LinkedHashMap

/**
 * Reports unused private members (fields/methods/classes) in the current file.
 *
 * Params (v1):
 * - check: optional object { fields?: bool, methods?: bool, classes?: bool } (defaults: fields=true, methods=true, classes=false)
 * - ignoreIfAnnotatedWithExact / Prefix: optional lists for member annotations
 * - ignoreIfContainingClassAnnotatedWithExact / Prefix: optional lists for containing class annotations
 * - ignoreRoles: optional list of roleIds (skip checking classes in these roles)
 * - ignoreNameRegex: optional list of regex patterns; matching member names are skipped
 *
 * Notes:
 * - Uses ReferencesSearch with project scope. Cancellation-aware for IDE safety.
 */
class DeadcodeUnusedPrivateMembersRule : EngineRule {
    override val id: String = "deadcode.unusedPrivateMembers"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val ruleInstanceId = RuleUtil.ruleInstanceId(rule, fallbackEngineRuleId = id)
        val p = Params.of(rule.params, "rules.${rule.type}.${rule.name}.params")

        val check = readCheck(p, rule.params["check"])
        val ignoreRoles =
            p
                .optionalStringList("ignoreRoles")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?.ifEmpty { null }

        val ignoreNameRegexes =
            (p.optionalStringList("ignoreNameRegex") ?: emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { rx ->
                    try {
                        Regex(rx)
                    } catch (_: Throwable) {
                        throw ParamError("${p.currentPath}.ignoreNameRegex", "Invalid regex '$rx'")
                    }
                }

        val ignoreMemberExact = normSet(p.optionalStringList("ignoreIfAnnotatedWithExact"))
        val ignoreMemberPrefix = normSet(p.optionalStringList("ignoreIfAnnotatedWithPrefix"))
        val ignoreClassExact = normSet(p.optionalStringList("ignoreIfContainingClassAnnotatedWithExact"))
        val ignoreClassPrefix = normSet(p.optionalStringList("ignoreIfContainingClassAnnotatedWithPrefix"))

        if (!check.fields && !check.methods && !check.classes) return emptyList()

        val sev = RuleUtil.severity(rule)
        val filePath = normalizePath(file.virtualFile?.path ?: file.name)
        val project = file.project
        val scope = GlobalSearchScope.projectScope(project)

        val out = ArrayList<Finding>()

        val psiClasses = PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)

        for (cls in psiClasses) {
            ProgressManager.checkCanceled()

            val classFqn = cls.qualifiedName ?: continue
            val role = facts.classToRole[classFqn]
            if (ignoreRoles != null && role != null && role in ignoreRoles) continue

            if (shouldIgnoreClassByAnnotation(cls, ignoreClassExact, ignoreClassPrefix)) continue

            if (check.fields) {
                for (field in cls.fields) {
                    ProgressManager.checkCanceled()
                    if (!field.hasModifierProperty(PsiModifier.PRIVATE)) continue
                    if (field.name == "serialVersionUID") continue

                    if (shouldIgnoreMember(field, cls, ignoreNameRegexes, ignoreMemberExact, ignoreMemberPrefix)) continue

                    if (isUsed(field, scope, cls)) continue

                    out +=
                        Finding(
                            ruleId = ruleInstanceId,
                            message = "Private field '${field.name}' appears unused.",
                            filePath = filePath,
                            severity = sev,
                            classFqn = classFqn,
                            memberName = field.name,
                            data = mapOf("memberKind" to "field"),
                        )
                }
            }

            if (check.methods) {
                for (m in cls.methods) {
                    ProgressManager.checkCanceled()
                    if (!m.hasModifierProperty(PsiModifier.PRIVATE)) continue
                    if (m.isConstructor) continue
                    if (m.name == "main" && m.hasModifierProperty(PsiModifier.STATIC)) continue

                    if (shouldIgnoreMember(m, cls, ignoreNameRegexes, ignoreMemberExact, ignoreMemberPrefix)) continue

                    if (isUsed(m, scope, cls)) continue

                    out +=
                        Finding(
                            ruleId = ruleInstanceId,
                            message = "Private method '${m.name}' appears unused.",
                            filePath = filePath,
                            severity = sev,
                            classFqn = classFqn,
                            memberName = m.name,
                            data = mapOf("memberKind" to "method"),
                        )
                }
            }

            if (check.classes) {
                // private nested classes only (top-level private classes are not valid in Java; Kotlin has internal/private top-level but represented differently)
                for (inner in cls.innerClasses) {
                    ProgressManager.checkCanceled()
                    if (!inner.hasModifierProperty(PsiModifier.PRIVATE)) continue

                    if (shouldIgnoreClassByAnnotation(inner, ignoreMemberExact, ignoreMemberPrefix)) continue

                    val anyRef = ReferencesSearch.search(inner, scope).findFirst()
                    if (anyRef == null) {
                        val innerFqn = inner.qualifiedName ?: continue
                        out +=
                            Finding(
                                ruleId = ruleInstanceId,
                                message = "Private nested class '${inner.name}' appears unused.",
                                filePath = filePath,
                                severity = sev,
                                classFqn = innerFqn,
                                memberName = inner.name,
                                data = mapOf("memberKind" to "class"),
                            )
                    }
                }
            }
        }

        return out
    }

    private data class Check(
        val fields: Boolean,
        val methods: Boolean,
        val classes: Boolean,
    )

    private fun readCheck(
        p: Params,
        raw: Any?,
    ): Check {
        // defaults: fields=true, methods=true, classes=false
        if (raw == null) return Check(fields = true, methods = true, classes = false)
        if (raw !is Map<*, *>) throw ParamError("${p.currentPath}.check", "must be an object/map")

        val ordered = LinkedHashMap<String, Any?>(raw.size)
        for ((k, v) in raw) {
            if (k == null) throw ParamError("${p.currentPath}.check", "map key must not be null")
            ordered[k.toString()] = v
        }
        val cp = Params.of(ordered, "${p.currentPath}.check")

        val fields = cp.optionalBoolean("fields") ?: true
        val methods = cp.optionalBoolean("methods") ?: true
        val classes = cp.optionalBoolean("classes") ?: false

        return Check(fields, methods, classes)
    }

    private fun normSet(list: List<String>?): Set<String> = (list ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun shouldIgnoreClassByAnnotation(
        cls: PsiModifierListOwner,
        exact: Set<String>,
        prefix: Set<String>,
    ): Boolean {
        if (exact.isEmpty() && prefix.isEmpty()) return false
        val anns = annotationFqns(cls)
        return anns.any { a -> a in exact || prefix.any { p -> a.startsWith(p) } }
    }

    private fun shouldIgnoreMember(
        member: PsiMember,
        containingClass: PsiClass,
        ignoreNameRegexes: List<Regex>,
        ignoreMemberExact: Set<String>,
        ignoreMemberPrefix: Set<String>,
    ): Boolean {
        val name = member.name ?: return false
        if (ignoreNameRegexes.any { it.containsMatchIn(name) }) return true

        if (ignoreMemberExact.isEmpty() && ignoreMemberPrefix.isEmpty()) return false
        val anns = annotationFqns(member)
        if (anns.any { a -> a in ignoreMemberExact || ignoreMemberPrefix.any { p -> a.startsWith(p) } }) return true

        // Also ignore if containing class has matching annotation lists in member scope (spec covers this via separate keys,
        // but we already processed those at the class loop level).
        return false
    }

    private fun annotationFqns(owner: PsiModifierListOwner): List<String> =
        owner.modifierList
            ?.annotations
            ?.mapNotNull { it.qualifiedName }
            .orEmpty()

    private fun isUsed(
        member: PsiMember,
        scope: GlobalSearchScope,
        cls: PsiClass,
    ): Boolean {
        // Local scan first: any reference inside the class counts as used.
        val localRefs = ReferencesSearch.search(member, cls.useScope).findAll()
        if (localRefs.any { it.element != member }) return true

        // Global fallback
        return ReferencesSearch.search(member, scope).findFirst() != null
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
