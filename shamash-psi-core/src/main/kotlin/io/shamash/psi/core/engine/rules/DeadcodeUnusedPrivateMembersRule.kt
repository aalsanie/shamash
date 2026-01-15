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
package io.shamash.psi.core.engine.rules

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.engine.EngineRule
import io.shamash.psi.core.facts.model.v1.FactsIndex
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UastVisibility
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.util.LinkedHashMap

/**
 * Reports unused private members (fields/methods/classes) in the current file.
 *
 * Uses UAST when available (covers Kotlin + Java).
 * Falls back to PSI-only traversal when UAST cannot be built.
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
 * - For Kotlin, UAST exposes synthetic methods (e.g., property accessors) with no sourcePsi.
 * - Those are ignored to avoid false positives.
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
        val scope = GlobalSearchScope.projectScope(file.project)

        val uFile = file.toUElementOfType<UFile>()
        return if (uFile != null) {
            evaluateUast(
                uFile = uFile,
                file = file,
                facts = facts,
                ruleInstanceId = ruleInstanceId,
                sev = sev,
                filePath = filePath,
                scope = scope,
                check = check,
                ignoreRoles = ignoreRoles,
                ignoreNameRegexes = ignoreNameRegexes,
                ignoreMemberExact = ignoreMemberExact,
                ignoreMemberPrefix = ignoreMemberPrefix,
                ignoreClassExact = ignoreClassExact,
                ignoreClassPrefix = ignoreClassPrefix,
            )
        } else {
            evaluatePsi(
                file = file,
                facts = facts,
                ruleInstanceId = ruleInstanceId,
                sev = sev,
                filePath = filePath,
                scope = scope,
                check = check,
                ignoreRoles = ignoreRoles,
                ignoreNameRegexes = ignoreNameRegexes,
                ignoreMemberExact = ignoreMemberExact,
                ignoreMemberPrefix = ignoreMemberPrefix,
                ignoreClassExact = ignoreClassExact,
                ignoreClassPrefix = ignoreClassPrefix,
            )
        }
    }

    private fun evaluateUast(
        uFile: UFile,
        file: PsiFile,
        facts: FactsIndex,
        ruleInstanceId: String,
        sev: FindingSeverity,
        filePath: String,
        scope: GlobalSearchScope,
        check: Check,
        ignoreRoles: Set<String>?,
        ignoreNameRegexes: List<Regex>,
        ignoreMemberExact: Set<String>,
        ignoreMemberPrefix: Set<String>,
        ignoreClassExact: Set<String>,
        ignoreClassPrefix: Set<String>,
    ): List<Finding> {
        val out = ArrayList<Finding>()

        uFile.accept(
            object : AbstractUastVisitor() {
                override fun visitClass(node: UClass): Boolean {
                    ProgressManager.checkCanceled()

                    val classFqn = node.qualifiedName
                    if (classFqn.isNullOrBlank()) return false

                    val role = facts.classToRole[classFqn]
                    if (ignoreRoles != null && role != null && role in ignoreRoles) return false

                    if (shouldIgnoreByAnnotation(node.annotations, ignoreClassExact, ignoreClassPrefix)) return false

                    val classPsi: PsiElement? = node.sourcePsi ?: node.javaPsi
                    val localScope: SearchScope = classPsi?.useScope ?: file.useScope

                    if (check.fields) {
                        for (field in node.fields) {
                            ProgressManager.checkCanceled()
                            if (!isPrivate(field)) continue
                            if (field.name == "serialVersionUID") continue

                            if (
                                shouldIgnoreUastMember(
                                    name = field.name,
                                    annotations = field.annotations,
                                    ignoreNameRegexes = ignoreNameRegexes,
                                    ignoreMemberExact = ignoreMemberExact,
                                    ignoreMemberPrefix = ignoreMemberPrefix,
                                )
                            ) {
                                continue
                            }

                            val anchor = (field.sourcePsi ?: field.javaPsi) as? PsiElement ?: continue
                            if (isUsed(anchor, localScope, scope)) continue

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
                        for (m in node.methods) {
                            ProgressManager.checkCanceled()
                            if (!isPrivate(m)) continue
                            if (m.isConstructor) continue

                            // Kotlin synthetic methods (e.g., property accessors) typically have no sourcePsi.
                            // Ignore them to avoid false positives.
                            if (m.sourcePsi == null) continue

                            val anchor = (m.sourcePsi ?: m.javaPsi) as? PsiElement ?: continue

                            // Keep the small "main" skip for Java.
                            if (anchor is PsiMethod) {
                                if (m.name == "main" && anchor.hasModifierProperty(PsiModifier.STATIC)) continue
                            }

                            if (
                                shouldIgnoreUastMember(
                                    name = m.name,
                                    annotations = m.annotations,
                                    ignoreNameRegexes = ignoreNameRegexes,
                                    ignoreMemberExact = ignoreMemberExact,
                                    ignoreMemberPrefix = ignoreMemberPrefix,
                                )
                            ) {
                                continue
                            }

                            if (isUsed(anchor, localScope, scope)) continue

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
                        for (inner in node.innerClasses) {
                            ProgressManager.checkCanceled()
                            if (!isPrivate(inner)) continue
                            if (shouldIgnoreByAnnotation(inner.annotations, ignoreMemberExact, ignoreMemberPrefix)) continue

                            val anchor = (inner.sourcePsi ?: inner.javaPsi) as? PsiElement ?: continue
                            if (ReferencesSearch.search(anchor, scope).findFirst() != null) continue

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

                    return false
                }
            },
        )

        return out
    }

    private fun evaluatePsi(
        file: PsiFile,
        facts: FactsIndex,
        ruleInstanceId: String,
        sev: FindingSeverity,
        filePath: String,
        scope: GlobalSearchScope,
        check: Check,
        ignoreRoles: Set<String>?,
        ignoreNameRegexes: List<Regex>,
        ignoreMemberExact: Set<String>,
        ignoreMemberPrefix: Set<String>,
        ignoreClassExact: Set<String>,
        ignoreClassPrefix: Set<String>,
    ): List<Finding> {
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

                    if (shouldIgnoreMember(field, ignoreNameRegexes, ignoreMemberExact, ignoreMemberPrefix)) continue
                    if (isUsed(field, cls.useScope, scope)) continue

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

                    if (shouldIgnoreMember(m, ignoreNameRegexes, ignoreMemberExact, ignoreMemberPrefix)) continue
                    if (isUsed(m, cls.useScope, scope)) continue

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
                // private nested classes only
                for (inner in cls.innerClasses) {
                    ProgressManager.checkCanceled()
                    if (!inner.hasModifierProperty(PsiModifier.PRIVATE)) continue

                    if (shouldIgnoreClassByAnnotation(inner, ignoreMemberExact, ignoreMemberPrefix)) continue
                    if (ReferencesSearch.search(inner, scope).findFirst() != null) continue

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

    private fun shouldIgnoreByAnnotation(
        annotations: List<UAnnotation>,
        exact: Set<String>,
        prefix: Set<String>,
    ): Boolean = shouldIgnoreByAnnotationFqns(annotations.mapNotNull { it.qualifiedName }, exact, prefix)

    // Compatibility: some platform/UAST variants surface annotations as PSI annotations arrays.
    private fun shouldIgnoreByAnnotation(
        annotations: Array<out PsiAnnotation>,
        exact: Set<String>,
        prefix: Set<String>,
    ): Boolean = shouldIgnoreByAnnotationFqns(annotations.mapNotNull { it.qualifiedName }, exact, prefix)

    private fun shouldIgnoreUastMember(
        name: String?,
        annotations: List<UAnnotation>,
        ignoreNameRegexes: List<Regex>,
        ignoreMemberExact: Set<String>,
        ignoreMemberPrefix: Set<String>,
    ): Boolean =
        shouldIgnoreMemberByAnnotationFqns(
            name = name,
            annotationFqns = annotations.mapNotNull { it.qualifiedName },
            ignoreNameRegexes = ignoreNameRegexes,
            ignoreMemberExact = ignoreMemberExact,
            ignoreMemberPrefix = ignoreMemberPrefix,
        )

    private fun shouldIgnoreUastMember(
        name: String?,
        annotations: Array<out PsiAnnotation>,
        ignoreNameRegexes: List<Regex>,
        ignoreMemberExact: Set<String>,
        ignoreMemberPrefix: Set<String>,
    ): Boolean =
        shouldIgnoreMemberByAnnotationFqns(
            name = name,
            annotationFqns = annotations.mapNotNull { it.qualifiedName },
            ignoreNameRegexes = ignoreNameRegexes,
            ignoreMemberExact = ignoreMemberExact,
            ignoreMemberPrefix = ignoreMemberPrefix,
        )

    private fun shouldIgnoreByAnnotationFqns(
        annotationFqns: List<String>,
        exact: Set<String>,
        prefix: Set<String>,
    ): Boolean {
        if (exact.isEmpty() && prefix.isEmpty()) return false
        return annotationFqns.any { a -> a in exact || prefix.any { p -> a.startsWith(p) } }
    }

    private fun shouldIgnoreMemberByAnnotationFqns(
        name: String?,
        annotationFqns: List<String>,
        ignoreNameRegexes: List<Regex>,
        ignoreMemberExact: Set<String>,
        ignoreMemberPrefix: Set<String>,
    ): Boolean {
        if (!name.isNullOrBlank()) {
            if (ignoreNameRegexes.any { it.containsMatchIn(name) }) return true
        }
        if (ignoreMemberExact.isEmpty() && ignoreMemberPrefix.isEmpty()) return false
        return annotationFqns.any { a -> a in ignoreMemberExact || ignoreMemberPrefix.any { p -> a.startsWith(p) } }
    }

    private fun isPrivate(decl: UDeclaration): Boolean {
        if (decl.visibility == UastVisibility.PRIVATE) return true
        val psi = (decl.sourcePsi ?: decl.javaPsi) as? PsiModifierListOwner
        return psi?.hasModifierProperty(PsiModifier.PRIVATE) == true
    }

    private fun shouldIgnoreClassByAnnotation(
        cls: PsiModifierListOwner,
        exact: Set<String>,
        prefix: Set<String>,
    ): Boolean {
        if (exact.isEmpty() && prefix.isEmpty()) return false
        val anns =
            cls.modifierList
                ?.annotations
                ?.mapNotNull { it.qualifiedName }
                .orEmpty()
        return anns.any { a -> a in exact || prefix.any { p -> a.startsWith(p) } }
    }

    private fun shouldIgnoreMember(
        member: PsiMember,
        ignoreNameRegexes: List<Regex>,
        ignoreMemberExact: Set<String>,
        ignoreMemberPrefix: Set<String>,
    ): Boolean {
        val name = member.name ?: return false
        if (ignoreNameRegexes.any { it.containsMatchIn(name) }) return true
        if (ignoreMemberExact.isEmpty() && ignoreMemberPrefix.isEmpty()) return false

        val anns =
            (member as? PsiModifierListOwner)
                ?.modifierList
                ?.annotations
                ?.mapNotNull { it.qualifiedName }
                .orEmpty()
        return anns.any { a -> a in ignoreMemberExact || ignoreMemberPrefix.any { p -> a.startsWith(p) } }
    }

    private fun isUsed(
        element: PsiElement,
        localScope: SearchScope,
        globalScope: GlobalSearchScope,
    ): Boolean {
        // Local scan first (cheap).
        if (ReferencesSearch.search(element, localScope).findFirst() != null) return true
        // Global fallback.
        return ReferencesSearch.search(element, globalScope).findFirst() != null
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
