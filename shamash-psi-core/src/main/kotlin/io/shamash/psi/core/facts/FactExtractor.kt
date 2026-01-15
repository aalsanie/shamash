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
package io.shamash.psi.core.facts

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.core.facts.kotlin.KotlinAnalysisCallOwnerResolver
import io.shamash.psi.core.facts.model.v1.ClassFact
import io.shamash.psi.core.facts.model.v1.DependencyFact
import io.shamash.psi.core.facts.model.v1.DependencyKind
import io.shamash.psi.core.facts.model.v1.FactsIndex
import io.shamash.psi.core.facts.model.v1.FieldFact
import io.shamash.psi.core.facts.model.v1.MethodFact
import io.shamash.psi.core.facts.model.v1.Visibility
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UField
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Kotlin+Java facts extraction.
 *
 * - Uses UAST when available (covers Kotlin bodies/calls/properties and Java too).
 * - Falls back to Java PSI when UAST isn't available.
 *
 * IDE-safe:
 * - cancellation aware
 * - deterministic output
 * - best-effort extraction + structured errors
 */
object FactExtractor {
    private val log = Logger.getInstance(FactExtractor::class.java)

    private data class CacheEntry(
        val stamp: Long,
        val result: FactsResult,
    )

    private val CACHE_KEY: Key<CacheEntry> = Key.create("shamash.psi.facts.cache.v2")

    /**
     * Back-compat API: returns facts only.
     * Prefer [extractResult] for production usage.
     */
    fun extract(file: PsiFile): FactsIndex = extractResult(file).facts

    /**
     * Production API: facts + structured errors.
     */
    fun extractResult(file: PsiFile): FactsResult {
        ProgressManager.checkCanceled()

        val stamp = file.modificationStamp
        val cached = file.getUserData(CACHE_KEY)
        if (cached != null && cached.stamp == stamp) return cached.result

        val fileId = safeFileId(file)

        val result =
            try {
                computeFactsResult(file, fileId)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                // Keep scan alive. Provide error to caller. Log only debug to avoid noisy logs in real projects.
                log.debug("Facts extraction failed for file=$fileId", t)
                FactsResult(
                    facts = emptyFacts(),
                    errors =
                        listOf(
                            FactsError(
                                fileId = fileId,
                                phase = "computeFacts",
                                message = t.message ?: "Facts extraction failed",
                                throwableClass = t::class.java.name,
                            ),
                        ),
                )
            }

        // Determinism barrier: sort facts + errors.
        val stabilized = result.stabilize()

        file.putUserData(CACHE_KEY, CacheEntry(stamp, stabilized))
        return stabilized
    }

    private fun computeFactsResult(
        file: PsiFile,
        fileId: String,
    ): FactsResult {
        ProgressManager.checkCanceled()

        val filePath = normalizeFilePath(file)
        val errors = mutableListOf<FactsError>()

        val uFile =
            try {
                file.toUElementOfType<UFile>()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                errors +=
                    FactsError(
                        fileId = fileId,
                        phase = "toUElementOfType",
                        message = t.message ?: "Failed to convert file to UAST",
                        throwableClass = t::class.java.name,
                    )
                null
            }

        val facts =
            if (uFile != null) {
                extractFromUast(uFile, filePath, fileId, errors)
            } else {
                extractFromJavaPsi(file, filePath, fileId, errors)
            }

        return FactsResult(facts = facts, errors = errors)
    }

    private fun extractFromUast(
        uFile: UFile,
        filePath: String,
        fileId: String,
        errors: MutableList<FactsError>,
    ): FactsIndex {
        val classes = mutableListOf<ClassFact>()
        val methods = mutableListOf<MethodFact>()
        val fields = mutableListOf<FieldFact>()
        val deps = mutableListOf<DependencyFact>()

        fun record(
            phase: String,
            t: Throwable,
        ) {
            if (t is ProcessCanceledException) throw t
            errors +=
                FactsError(
                    fileId = fileId,
                    phase = phase,
                    message = t.message ?: "UAST extraction error",
                    throwableClass = t::class.java.name,
                )
        }

        uFile.accept(
            object : AbstractUastVisitor() {
                override fun visitClass(node: UClass): Boolean {
                    ProgressManager.checkCanceled()
                    return try {
                        val fqn = node.qualifiedName ?: return false
                        val pkg = fqn.substringBeforeLast('.', "")
                        val annotations = node.uAnnotations.mapNotNull { it.qualifiedName }.toSet()

                        val psiClass = node.javaPsi
                        val superFqn = psiClass.superClass?.qualifiedName
                        val ifaces = psiClass.interfaces.mapNotNull { it.qualifiedName }.toSet()

                        val hasMain =
                            psiClass.methods.any { m ->
                                m.name == "main" &&
                                    m.hasModifierProperty(PsiModifier.PUBLIC) &&
                                    m.hasModifierProperty(PsiModifier.STATIC) &&
                                    m.parameterList.parametersCount == 1
                            }

                        val range = safeRangeU(node)

                        classes +=
                            ClassFact(
                                fqName = fqn,
                                packageName = pkg,
                                simpleName = node.name ?: fqn.substringAfterLast('.'),
                                annotationsFqns = annotations,
                                superClassFqn = superFqn,
                                interfacesFqns = ifaces,
                                hasMainMethod = hasMain,
                                filePath = filePath,
                                textRange = range,
                            )

                        if (superFqn != null) addDep(deps, fqn, superFqn, DependencyKind.EXTENDS, filePath, range, "extends")
                        for (i in ifaces) addDep(deps, fqn, i, DependencyKind.IMPLEMENTS, filePath, range, "implements")
                        for (a in annotations) addDep(deps, fqn, a, DependencyKind.ANNOTATION_TYPE, filePath, range, "class-annotation")

                        false
                    } catch (t: Throwable) {
                        record("uast:visitClass", t)
                        false
                    }
                }

                override fun visitMethod(node: UMethod): Boolean {
                    ProgressManager.checkCanceled()
                    return try {
                        val fromClassFqn = containingClassFqnOfU(node) ?: return false

                        val anns = node.uAnnotations.mapNotNull { it.qualifiedName }.toSet()
                        val paramDepTypes = node.uastParameters.mapNotNull { p -> normalizeDepType(p.type) }
                        val retDepType = node.returnType?.let { normalizeDepType(it) }

                        val psiMethod: PsiMethod? = node.javaPsi
                        val range = safeRangeU(node)

                        methods +=
                            MethodFact(
                                containingClassFqn = fromClassFqn,
                                name = node.name,
                                signature = buildSignature(node),
                                visibility = visibilityOf(psiMethod as PsiElement?),
                                isStatic = psiMethod?.hasModifierProperty(PsiModifier.STATIC) ?: false,
                                isAbstract = psiMethod?.hasModifierProperty(PsiModifier.ABSTRACT) ?: false,
                                isConstructor = node.isConstructor,
                                returnTypeFqn = retDepType,
                                parameterTypeFqns = paramDepTypes,
                                annotationsFqns = anns,
                                filePath = filePath,
                                textRange = range,
                            )

                        for (p in paramDepTypes) {
                            addDep(
                                deps,
                                fromClassFqn,
                                p,
                                DependencyKind.PARAMETER_TYPE,
                                filePath,
                                range,
                                "param:${node.name}",
                            )
                        }
                        if (retDepType !=
                            null
                        ) {
                            addDep(deps, fromClassFqn, retDepType, DependencyKind.RETURN_TYPE, filePath, range, "return:${node.name}")
                        }
                        for (a in anns) {
                            addDep(
                                deps,
                                fromClassFqn,
                                a,
                                DependencyKind.ANNOTATION_TYPE,
                                filePath,
                                range,
                                "method-annotation:${node.name}",
                            )
                        }

                        false
                    } catch (t: Throwable) {
                        record("uast:visitMethod", t)
                        false
                    }
                }

                override fun visitField(node: UField): Boolean {
                    ProgressManager.checkCanceled()
                    return try {
                        val fromClassFqn = containingClassFqnOfU(node) ?: return false

                        val anns = node.uAnnotations.mapNotNull { it.qualifiedName }.toSet()
                        val range = safeRangeU(node)

                        val psiOwner = node.javaPsi as? PsiModifierListOwner
                        val isStatic = psiOwner?.hasModifierProperty(PsiModifier.STATIC) ?: false
                        val isFinal = psiOwner?.hasModifierProperty(PsiModifier.FINAL) ?: false

                        fields +=
                            FieldFact(
                                containingClassFqn = fromClassFqn,
                                name = node.name,
                                typeFqn = normalizeFactType(node.type),
                                visibility = visibilityOf(node.javaPsi),
                                isStatic = isStatic,
                                isFinal = isFinal,
                                annotationsFqns = anns,
                                filePath = filePath,
                                textRange = range,
                            )

                        normalizeDepType(node.type)?.let { typeFqn ->
                            addDep(deps, fromClassFqn, typeFqn, DependencyKind.FIELD_TYPE, filePath, range, "field:${node.name}")
                        }
                        for (a in anns) {
                            addDep(
                                deps,
                                fromClassFqn,
                                a,
                                DependencyKind.ANNOTATION_TYPE,
                                filePath,
                                range,
                                "field-annotation:${node.name}",
                            )
                        }

                        false
                    } catch (t: Throwable) {
                        record("uast:visitField", t)
                        false
                    }
                }

                override fun visitCallExpression(node: UCallExpression): Boolean {
                    ProgressManager.checkCanceled()
                    return try {
                        val fromClassFqn = containingClassFqnOfU(node) ?: return false
                        val range = safeRangeU(node)

                        when (node.kind) {
                            UastCallKind.METHOD_CALL -> {
                                val resolved = node.resolve()
                                val owner = resolved?.containingClass?.qualifiedName

                                if (owner != null) {
                                    addDep(deps, fromClassFqn, owner, DependencyKind.METHOD_CALL, filePath, range, "call:${resolved.name}")
                                } else {
                                    val ktExpr = node.sourcePsi as? KtExpression
                                    if (ktExpr != null) {
                                        val kOwner = KotlinAnalysisCallOwnerResolver.resolveOwnerClassFqn(ktExpr)
                                        if (kOwner != null) {
                                            addDep(
                                                deps,
                                                fromClassFqn,
                                                kOwner,
                                                DependencyKind.METHOD_CALL,
                                                filePath,
                                                range,
                                                "call:kotlin-analysis",
                                            )
                                        }
                                    }
                                }
                            }

                            UastCallKind.CONSTRUCTOR_CALL -> {
                                val resolved = node.resolve()
                                val owner = resolved?.containingClass?.qualifiedName
                                if (owner != null) {
                                    addDep(deps, fromClassFqn, owner, DependencyKind.METHOD_CALL, filePath, range, "new")
                                } else {
                                    val ktExpr = node.sourcePsi as? KtExpression
                                    if (ktExpr != null) {
                                        val kOwner = KotlinAnalysisCallOwnerResolver.resolveOwnerClassFqn(ktExpr)
                                        if (kOwner != null) {
                                            addDep(
                                                deps,
                                                fromClassFqn,
                                                kOwner,
                                                DependencyKind.METHOD_CALL,
                                                filePath,
                                                range,
                                                "new:kotlin-analysis",
                                            )
                                        }
                                    }
                                }
                            }

                            else -> Unit
                        }

                        false
                    } catch (t: Throwable) {
                        record("uast:visitCallExpression", t)
                        false
                    }
                }
            },
        )

        return FactsIndex(
            classes = classes,
            methods = methods,
            fields = fields,
            dependencies = deps.dedupeStable(),
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    private fun extractFromJavaPsi(
        file: PsiFile,
        filePath: String,
        fileId: String,
        errors: MutableList<FactsError>,
    ): FactsIndex {
        val classes = mutableListOf<ClassFact>()
        val methods = mutableListOf<MethodFact>()
        val fields = mutableListOf<FieldFact>()
        val deps = mutableListOf<DependencyFact>()

        fun record(
            phase: String,
            t: Throwable,
        ) {
            if (t is ProcessCanceledException) throw t
            errors +=
                FactsError(
                    fileId = fileId,
                    phase = phase,
                    message = t.message ?: "PSI extraction error",
                    throwableClass = t::class.java.name,
                )
        }

        file.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitClass(aClass: PsiClass) {
                    ProgressManager.checkCanceled()
                    try {
                        super.visitClass(aClass)

                        val fqn = aClass.qualifiedName ?: return
                        val pkg = fqn.substringBeforeLast('.', "")
                        val annotations =
                            aClass.modifierList
                                ?.annotations
                                ?.mapNotNull { it.qualifiedName }
                                ?.toSet() ?: emptySet()

                        val superFqn = aClass.superClass?.qualifiedName
                        val ifaces = aClass.interfaces.mapNotNull { it.qualifiedName }.toSet()

                        val hasMain =
                            aClass.methods.any { m ->
                                m.name == "main" &&
                                    m.hasModifierProperty(PsiModifier.STATIC) &&
                                    m.hasModifierProperty(PsiModifier.PUBLIC) &&
                                    m.parameterList.parametersCount == 1
                            }

                        val range = safeRangePsi(aClass)

                        classes +=
                            ClassFact(
                                fqName = fqn,
                                packageName = pkg,
                                simpleName = aClass.name ?: fqn.substringAfterLast('.'),
                                annotationsFqns = annotations,
                                superClassFqn = superFqn,
                                interfacesFqns = ifaces,
                                hasMainMethod = hasMain,
                                filePath = filePath,
                                textRange = range,
                            )

                        superFqn?.let { addDep(deps, fqn, it, DependencyKind.EXTENDS, filePath, range, "extends") }
                        for (i in ifaces) addDep(deps, fqn, i, DependencyKind.IMPLEMENTS, filePath, range, "implements")
                        for (a in annotations) addDep(deps, fqn, a, DependencyKind.ANNOTATION_TYPE, filePath, range, "class-annotation")
                    } catch (t: Throwable) {
                        record("psi:visitClass", t)
                    }
                }

                override fun visitField(field: PsiField) {
                    ProgressManager.checkCanceled()
                    try {
                        super.visitField(field)

                        val cls = field.containingClass?.qualifiedName ?: return
                        val anns =
                            field.modifierList
                                ?.annotations
                                ?.mapNotNull { it.qualifiedName }
                                ?.toSet() ?: emptySet()
                        val range = safeRangePsi(field)

                        fields +=
                            FieldFact(
                                containingClassFqn = cls,
                                name = field.name,
                                typeFqn = normalizeFactType(field.type),
                                visibility = visibilityOf(field),
                                isStatic = field.hasModifierProperty(PsiModifier.STATIC),
                                isFinal = field.hasModifierProperty(PsiModifier.FINAL),
                                annotationsFqns = anns,
                                filePath = filePath,
                                textRange = range,
                            )

                        normalizeDepType(field.type)?.let { typeFqn ->
                            addDep(deps, cls, typeFqn, DependencyKind.FIELD_TYPE, filePath, range, "field:${field.name}")
                        }
                        for (a in anns) {
                            addDep(
                                deps,
                                cls,
                                a,
                                DependencyKind.ANNOTATION_TYPE,
                                filePath,
                                range,
                                "field-annotation:${field.name}",
                            )
                        }
                    } catch (t: Throwable) {
                        record("psi:visitField", t)
                    }
                }

                override fun visitMethod(method: PsiMethod) {
                    ProgressManager.checkCanceled()
                    try {
                        super.visitMethod(method)

                        val cls = method.containingClass?.qualifiedName ?: return
                        val anns =
                            method.modifierList.annotations
                                .mapNotNull { it.qualifiedName }
                                .toSet()
                        val range = safeRangePsi(method)

                        val paramsDep = method.parameterList.parameters.mapNotNull { normalizeDepType(it.type) }
                        val retDep = method.returnType?.let { normalizeDepType(it) }

                        methods +=
                            MethodFact(
                                containingClassFqn = cls,
                                name = method.name,
                                signature = buildSignature(method),
                                visibility = visibilityOf(method),
                                isStatic = method.hasModifierProperty(PsiModifier.STATIC),
                                isAbstract = method.hasModifierProperty(PsiModifier.ABSTRACT),
                                isConstructor = method.isConstructor,
                                returnTypeFqn = retDep,
                                parameterTypeFqns = paramsDep,
                                annotationsFqns = anns,
                                filePath = filePath,
                                textRange = range,
                            )

                        for (p in paramsDep) addDep(deps, cls, p, DependencyKind.PARAMETER_TYPE, filePath, range, "param:${method.name}")
                        if (retDep != null) addDep(deps, cls, retDep, DependencyKind.RETURN_TYPE, filePath, range, "return:${method.name}")
                        for (a in anns) {
                            addDep(
                                deps,
                                cls,
                                a,
                                DependencyKind.ANNOTATION_TYPE,
                                filePath,
                                range,
                                "method-annotation:${method.name}",
                            )
                        }

                        val body = method.body ?: return
                        body.accept(
                            object : JavaRecursiveElementVisitor() {
                                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                                    ProgressManager.checkCanceled()
                                    try {
                                        super.visitMethodCallExpression(expression)
                                        val target = expression.resolveMethod()
                                        val owner = target?.containingClass?.qualifiedName
                                        if (owner != null) {
                                            addDep(
                                                deps,
                                                cls,
                                                owner,
                                                DependencyKind.METHOD_CALL,
                                                filePath,
                                                safeRangePsi(expression),
                                                "call:${target.name}",
                                            )
                                        }
                                    } catch (t: Throwable) {
                                        record("psi:visitMethodCallExpression", t)
                                    }
                                }

                                override fun visitNewExpression(expression: PsiNewExpression) {
                                    ProgressManager.checkCanceled()
                                    try {
                                        super.visitNewExpression(expression)
                                        val resolved = expression.classReference?.resolve() as? PsiClass
                                        val ctorClass = resolved?.qualifiedName
                                        if (ctorClass != null) {
                                            addDep(
                                                deps,
                                                cls,
                                                ctorClass,
                                                DependencyKind.METHOD_CALL,
                                                filePath,
                                                safeRangePsi(expression),
                                                "new",
                                            )
                                        }
                                    } catch (t: Throwable) {
                                        record("psi:visitNewExpression", t)
                                    }
                                }
                            },
                        )
                    } catch (t: Throwable) {
                        record("psi:visitMethod", t)
                    }
                }
            },
        )

        return FactsIndex(
            classes = classes,
            methods = methods,
            fields = fields,
            dependencies = deps.dedupeStable(),
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    // ---------- determinism + helpers ----------

    private fun FactsResult.stabilize(): FactsResult =
        copy(
            facts = facts.stabilizeFacts(),
            errors = errors.sortedWith(compareBy({ it.phase }, { it.message }, { it.throwableClass.orEmpty() }, { it.fileId })),
        )

    private fun FactsIndex.stabilizeFacts(): FactsIndex =
        copy(
            classes = classes.sortedBy { it.fqName },
            methods = methods.sortedBy { it.fqName },
            fields = fields.sortedBy { it.fqName },
            dependencies = dependencies.sortedBy { depKey(it) },
        )

    private fun List<DependencyFact>.dedupeStable(): List<DependencyFact> {
        val seen = LinkedHashMap<String, DependencyFact>(this.size)
        for (d in this) {
            val k = depKey(d)
            if (!seen.containsKey(k)) seen[k] = d
        }
        return seen.values.sortedBy { depKey(it) }
    }

    private fun depKey(d: DependencyFact): String {
        val r = d.textRange
        val rangeKey = if (r == null) "" else "${r.startOffset}:${r.endOffset}"
        return "${d.fromClassFqn}|${d.toTypeFqn}|${d.kind.name}|${d.filePath}|${d.detail.orEmpty()}|$rangeKey"
    }

    private fun safeRangePsi(e: PsiElement): TextRange? =
        try {
            e.textRange
        } catch (_: Throwable) {
            null
        }

    private fun safeRangeU(u: UElement): TextRange? {
        val psi = u.sourcePsi ?: return null
        return safeRangePsi(psi)
    }

    private fun normalizeFactType(t: PsiType): String = t.canonicalText

    private fun normalizeDepType(t: PsiType): String? {
        if (t is PsiPrimitiveType) return null
        val text = t.canonicalText
        if (text == "void") return null

        var base = text.substringBefore('<')
        while (base.endsWith("[]")) base = base.removeSuffix("[]")

        return if (base.contains('.')) base else null
    }

    private fun buildSignature(m: PsiMethod): String {
        val params = m.parameterList.parameters.joinToString(",") { it.type.canonicalText }
        return if (m.isConstructor) {
            "${m.name}($params)"
        } else {
            "${m.name}($params):${m.returnType?.canonicalText ?: "void"}"
        }
    }

    private fun buildSignature(m: UMethod): String {
        val params = m.uastParameters.joinToString(",") { it.type.canonicalText }
        val ret = m.returnType?.canonicalText ?: "void"
        return if (m.isConstructor) "${m.name}($params)" else "${m.name}($params):$ret"
    }

    private fun visibilityOf(e: PsiElement?): Visibility {
        if (e == null) return Visibility.PACKAGE_PRIVATE
        val owner = e as? PsiModifierListOwner ?: return Visibility.PACKAGE_PRIVATE
        return when {
            owner.hasModifierProperty(PsiModifier.PUBLIC) -> Visibility.PUBLIC
            owner.hasModifierProperty(PsiModifier.PROTECTED) -> Visibility.PROTECTED
            owner.hasModifierProperty(PsiModifier.PRIVATE) -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }
    }

    private fun visibilityOf(m: PsiMethod): Visibility = visibilityOf(m as PsiElement)

    private fun visibilityOf(f: PsiField): Visibility = visibilityOf(f as PsiElement)

    private fun containingClassFqnOfU(u: UElement): String? {
        val psi = u.sourcePsi ?: return null

        val psiClass = PsiTreeUtil.getParentOfType(psi, PsiClass::class.java, false)
        val qn = psiClass?.qualifiedName
        if (qn != null) return qn

        if (psiClass != null) {
            val uClass = psiClass.toUElementOfType<UClass>()
            val uq = uClass?.qualifiedName
            if (uq != null) return uq
        }

        return null
    }

    private fun addDep(
        out: MutableList<DependencyFact>,
        from: String,
        to: String,
        kind: DependencyKind,
        filePath: String,
        range: TextRange?,
        detail: String?,
    ) {
        if (from == to) return
        if (to.isBlank()) return

        out +=
            DependencyFact(
                fromClassFqn = from,
                toTypeFqn = to,
                kind = kind,
                filePath = filePath,
                textRange = range,
                detail = detail,
            )
    }

    private fun emptyFacts(): FactsIndex =
        FactsIndex(
            classes = emptyList(),
            methods = emptyList(),
            fields = emptyList(),
            dependencies = emptyList(),
            roles = emptyMap(),
            classToRole = emptyMap(),
        )

    private fun normalizeFilePath(file: PsiFile): String = (file.virtualFile?.path ?: file.name).replace('\\', '/')

    private fun safeFileId(file: PsiFile): String =
        try {
            file.virtualFile?.path ?: file.name
        } catch (_: Throwable) {
            file.name
        }
}
