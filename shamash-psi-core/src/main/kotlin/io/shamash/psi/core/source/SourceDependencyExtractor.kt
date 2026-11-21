/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import java.util.concurrent.CancellationException

/** Resolves actual Java/Kotlin PSI references; never infers dependencies from package/import text. */
class SourceDependencyExtractor(
    private val sourceScope: (PsiFile) -> SourceLocation?,
) {
    data class SourceLocation(
        val moduleId: String,
        val sourceRoot: String,
        val fileUrl: String,
    )

    data class FileResult(
        val entities: List<SourceEntity>,
        val references: List<SourceDependency>,
        val diagnostics: List<SourceDiagnostic>,
    )

    fun extract(
        file: PsiFile,
        location: SourceLocation,
    ): FileResult {
        ProgressManager.checkCanceled()
        val entities = LinkedHashMap<SourceEntityId, SourceEntity>()
        val refs = ArrayList<SourceDependency>()
        val diagnostics = ArrayList<SourceDiagnostic>()
        val pendingUnresolved = ArrayList<Pair<SourceDependency, SourceDiagnostic>>()

        data class TypeUse(
            val owner: PsiElement,
            val type: PsiType,
            val evidence: PsiElement,
        )
        val typeUses = ArrayList<TypeUse>()
        val stamp = file.viewProvider.document?.modificationStamp ?: file.modificationStamp

        file.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    ProgressManager.checkCanceled()
                    entity(element, location)?.let { entities[it.id] = it }
                    when (element) {
                        is PsiVariable -> {
                            element.typeElement?.let { typeElement ->
                                typeUses += TypeUse(element, element.type, typeElement)
                            }
                        }

                        is PsiMethod -> {
                            element.returnType?.let { type ->
                                element.returnTypeElement?.let { typeElement ->
                                    typeUses += TypeUse(element, type, typeElement)
                                }
                            }
                        }
                    }
                    if (supportedReference(element) && !insideImport(element)) {
                        val from = owningEntity(element, location)
                        if (from == null) {
                            diagnostics +=
                                SourceDiagnostic(
                                    location.fileUrl,
                                    "owner",
                                    "No supported declaration owns reference at ${element.textOffset}",
                                )
                            return super.visitElement(element)
                        }
                        try {
                            val candidates = element.references
                            var resolvedCandidate = false
                            for (reference in candidates) {
                                ProgressManager.checkCanceled()
                                val resolved = reference.resolve()
                                val declaration = resolved?.let(::declarationOf)
                                if (element is KtCallExpression &&
                                    (
                                        (declaration as? KtNamedFunction)?.name != "invoke" ||
                                            element.calleeExpression?.text == "invoke"
                                    )
                                ) {
                                    continue
                                }
                                if (resolved != null && (declaration == null || isLocalBinding(declaration))) {
                                    resolvedCandidate = true
                                    continue
                                }
                                val targets = resolveTargets(resolved)
                                if (targets.isEmpty()) continue
                                resolvedCandidate = true
                                val evidenceRange = reference.rangeInElement
                                val start = evidenceRange.startOffset.coerceIn(0, element.textLength - 1)
                                val offset = element.textOffset + start
                                val length = evidenceRange.length.coerceIn(1, element.textLength - start)
                                for (target in targets) {
                                    if (target is SourceTarget.Internal && target.entity == from.id) continue
                                    if (target is SourceTarget.Unresolved) {
                                        diagnostics +=
                                            SourceDiagnostic(
                                                location.fileUrl,
                                                "resolve",
                                                "No supported target for '${element.text}' at ${element.textOffset}",
                                            )
                                    }
                                    refs += dependency(from.id, target, element, stamp, declaration, offset, length)
                                }
                            }
                            if (!resolvedCandidate && shouldReportUnresolved(element)) {
                                pendingUnresolved +=
                                    dependency(from.id, SourceTarget.Unresolved(element.text), element, stamp) to
                                    SourceDiagnostic(
                                        location.fileUrl,
                                        "resolve",
                                        "Cannot resolve '${element.text}' at ${element.textOffset}",
                                    )
                            }
                        } catch (c: ProcessCanceledException) {
                            throw c
                        } catch (c: CancellationException) {
                            throw ProcessCanceledException()
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw ProcessCanceledException()
                        } catch (t: Exception) {
                            rethrowSourceCancellation(t)
                            diagnostics +=
                                SourceDiagnostic(
                                    location.fileUrl,
                                    "resolve",
                                    "${t.javaClass.simpleName}: ${t.message ?: "resolution failed"}",
                                )
                            refs += dependency(from.id, SourceTarget.Unresolved(element.text), element, stamp)
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        // The generic walker covers wildcard/type-variable bounds which are easy to lose in a
        // class-only UAST projection. Ordinary PSI references win because they carry exact token ranges.
        val typeWitnesses = SourceTypeWitnessIndex(refs)
        for (use in typeUses) {
            ProgressManager.checkCanceled()
            val source = owningEntityFrom(use.owner, location)
            if (source == null) {
                diagnostics +=
                    SourceDiagnostic(location.fileUrl, "owner", "No supported declaration owns type at ${use.evidence.textOffset}")
                continue
            }
            val walked = PsiTypeClassReferences.collect(use.type)
            if (walked.truncated) {
                diagnostics +=
                    SourceDiagnostic(
                        location.fileUrl,
                        "type",
                        "Generic type nesting exceeded supported depth",
                    )
            }
            for (resolvedClass in walked.classes) {
                val target = resolveTargets(resolvedClass).firstOrNull() ?: continue
                if (target is SourceTarget.Internal && target.entity == source.id) continue
                if (typeWitnesses.contains(
                        source.id,
                        target,
                        use.evidence.textRange.startOffset,
                        use.evidence.textRange.endOffset,
                    )
                ) {
                    continue
                }
                val token = resolvedClass.name ?: continue
                if (!typeWitnesses.addMissing(
                        refs,
                        source.id,
                        target,
                        use.evidence.text,
                        use.evidence.textOffset,
                        token,
                        stamp,
                    )
                ) {
                    diagnostics +=
                        SourceDiagnostic(
                            location.fileUrl,
                            "type",
                            "Cannot uniquely locate generic type witness for $token",
                        )
                }
            }
        }

        for ((unresolved, diagnostic) in pendingUnresolved) {
            if (refs.none {
                    it.from == unresolved.from &&
                        it.target !is SourceTarget.Unresolved &&
                        it.evidence.offset == unresolved.evidence.offset
                }
            ) {
                refs += unresolved
                diagnostics += diagnostic
            }
        }

        return FileResult(
            entities.values.sortedWith(compareBy({ it.id.fileUrl }, { it.offset }, { it.id.signature })),
            refs.distinct().sortedWith(
                compareBy({ it.evidence.fileUrl }, { it.evidence.offset }, { it.kind.name }, { it.target.toString() }),
            ),
            diagnostics.distinct(),
        )
    }

    private fun resolveTargets(
        resolved: PsiElement?,
        visited: MutableSet<PsiElement> = HashSet(),
    ): List<SourceTarget> {
        if (resolved == null || !visited.add(resolved)) return emptyList()
        val declaration = declarationOf(resolved) ?: return emptyList()
        if (isLocalBinding(declaration)) return emptyList()
        if (declaration is KtTypeAlias) {
            val ref = declaration.getTypeReference() ?: return listOf(SourceTarget.Unresolved(declaration.name.orEmpty()))
            val names = PsiTreeUtil.findChildrenOfType(ref, KtNameReferenceExpression::class.java)
            if (names.isEmpty()) return listOf(SourceTarget.Unresolved(declaration.name.orEmpty()))
            return names
                .flatMap { name ->
                    val expanded = name.references.firstOrNull()?.resolve()
                    if (expanded == null) {
                        listOf(SourceTarget.Unresolved(name.text))
                    } else {
                        resolveTargets(expanded, visited)
                    }
                }.distinct()
        }
        val targetFile = declaration.containingFile ?: return emptyList()
        val location = sourceScope(targetFile)
        return if (location == null) {
            listOf(SourceTarget.External(declarationName(declaration)))
        } else {
            listOf(
                entity(declaration, location)?.let { SourceTarget.Internal(it.id) }
                    ?: SourceTarget.Unresolved(declarationName(declaration)),
            )
        }
    }

    internal fun declarationOf(element: PsiElement): PsiElement? {
        val nav = element.navigationElement.takeIf { it !== element && it.containingFile != null } ?: element
        return when (nav) {
            is PsiLocalVariable, is PsiParameter, is KtParameter,
            is PsiClass, is PsiMethod, is PsiField,
            is KtClassOrObject, is KtNamedFunction, is KtProperty, is KtTypeAlias,
            -> {
                nav
            }

            else -> {
                // Kotlin light elements may navigate to a name identifier rather than the declaration.
                var parent: PsiElement? = nav.parent
                while (parent != null && parent !is PsiFile) {
                    if (parent is KtParameter || parent is KtClassOrObject || parent is KtNamedFunction ||
                        parent is KtProperty || parent is KtTypeAlias
                    ) {
                        return parent
                    }
                    parent = parent.parent
                }
                element as? PsiMember
            }
        }
    }

    private fun supportedReference(e: PsiElement): Boolean =
        e is PsiJavaCodeReferenceElement ||
            e is PsiReferenceExpression ||
            e is KtNameReferenceExpression ||
            (e is KtOperationReferenceExpression && e.isConventionOperator()) ||
            e is KtArrayAccessExpression ||
            e is KtCallExpression

    private fun shouldReportUnresolved(element: PsiElement): Boolean =
        when (element) {
            is KtNameReferenceExpression -> !isLexicalLocalReference(element)
            is KtOperationReferenceExpression, is KtArrayAccessExpression -> true
            else -> element is PsiJavaCodeReferenceElement || element is PsiReferenceExpression
        }

    private fun isLexicalLocalReference(reference: KtNameReferenceExpression): Boolean {
        val name = reference.getReferencedName()
        var scope: PsiElement? = reference.parent
        while (scope != null && scope !is PsiFile) {
            if (scope is KtNamedFunction && scope.valueParameters.any { it.name == name }) return true
            scope = scope.parent
        }
        return false
    }

    private fun insideImport(e: PsiElement): Boolean {
        var parent: PsiElement? = e.parent
        while (parent != null && parent !is PsiFile) {
            if (parent is PsiImportStatementBase || parent is KtImportDirective ||
                parent is PsiPackageStatement || parent is KtPackageDirective
            ) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun owningEntity(
        reference: PsiElement,
        location: SourceLocation,
    ): SourceEntity? = owningEntityFrom(reference.parent, location)

    private fun owningEntityFrom(
        start: PsiElement?,
        location: SourceLocation,
    ): SourceEntity? {
        var current = start
        while (current != null && current !is PsiFile) {
            entity(current, location)?.let { return it }
            current = current.parent
        }
        return null
    }

    private fun isLocalBinding(declaration: PsiElement): Boolean =
        when (declaration) {
            is PsiLocalVariable, is PsiParameter, is KtParameter -> true
            is PsiClass -> declaration.qualifiedName == null
            is PsiMember -> declaration.containingClass?.qualifiedName == null
            is KtClassOrObject -> declaration.name == null || hasLocalBoundary(declaration)
            is KtProperty -> declaration.isLocal || hasLocalBoundary(declaration)
            is KtNamedFunction -> hasLocalBoundary(declaration)
            else -> false
        }

    private fun hasLocalBoundary(e: PsiElement): Boolean {
        var current = e.parent
        while (current != null && current !is PsiFile) {
            if (current is KtNamedFunction ||
                current is KtLambdaExpression ||
                (current is KtClassOrObject && current.name == null)
            ) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun entity(
        element: PsiElement,
        location: SourceLocation,
    ): SourceEntity? {
        val name: String
        val kind: SourceEntityKind
        val signature: String
        when (element) {
            is PsiClass -> {
                name = element.qualifiedName ?: return null
                kind = if (element.isInterface) SourceEntityKind.INTERFACE else SourceEntityKind.CLASS
                signature = name
            }

            is PsiMethod -> {
                name = element.name
                kind = SourceEntityKind.METHOD
                val ownerName = element.containingClass?.qualifiedName ?: return null
                val parameters = element.parameterList.parameters.joinToString(",") { it.type.canonicalText }
                signature = "$ownerName#${element.name}($parameters)"
            }

            is PsiField -> {
                name = element.name
                kind = SourceEntityKind.FIELD
                signature = "${element.containingClass?.qualifiedName ?: return null}#${element.name}"
            }

            is KtClassOrObject -> {
                if (element.name == null || hasLocalBoundary(element)) return null
                name = qualifiedKotlinName(element)
                kind =
                    when {
                        element is org.jetbrains.kotlin.psi.KtObjectDeclaration -> SourceEntityKind.OBJECT
                        element is KtClass && element.isInterface() -> SourceEntityKind.INTERFACE
                        else -> SourceEntityKind.CLASS
                    }
                signature = name
            }

            is KtNamedFunction -> {
                if (hasLocalBoundary(element)) return null
                name = element.name ?: return null
                kind = SourceEntityKind.FUNCTION
                val receiver =
                    element.receiverTypeReference
                        ?.text
                        ?.let { "$it." }
                        .orEmpty()
                signature = receiver + qualifiedKotlinName(element) + "(" +
                    element.valueParameters.joinToString(",") { it.typeReference?.text.orEmpty() } + ")"
            }

            is KtProperty -> {
                if (element.isLocal || hasLocalBoundary(element)) return null
                name = element.name ?: return null
                kind = SourceEntityKind.PROPERTY
                signature = element.receiverTypeReference
                    ?.text
                    ?.let { "$it." }
                    .orEmpty() + qualifiedKotlinName(element)
            }

            is KtTypeAlias -> {
                if (hasLocalBoundary(element)) return null
                name = element.name ?: return null
                kind = SourceEntityKind.TYPE_ALIAS
                signature = qualifiedKotlinName(element)
            }

            else -> {
                return null
            }
        }
        val id = SourceEntityId(location.moduleId, location.sourceRoot, location.fileUrl, kind, signature)
        val packageName =
            when (val f = element.containingFile) {
                is KtFile -> f.packageFqName.asString()
                is com.intellij.psi.PsiJavaFile -> f.packageName
                else -> ""
            }
        return SourceEntity(id, packageName, name, element.textOffset)
    }

    private fun qualifiedKotlinName(e: PsiElement): String {
        val pkg = (e.containingFile as? KtFile)?.packageFqName?.asString().orEmpty()
        val parents = ArrayList<String>()
        var node: PsiElement? = e
        while (node != null && node !is PsiFile) {
            when (node) {
                is KtClassOrObject -> node.name?.let(parents::add)
                is KtNamedFunction -> node.name?.let(parents::add)
                is KtProperty -> node.name?.let(parents::add)
                is KtTypeAlias -> node.name?.let(parents::add)
            }
            node = node.parent
        }
        return (listOf(pkg.takeIf { it.isNotBlank() }) + parents.asReversed()).filterNotNull().joinToString(".")
    }

    private fun declarationName(e: PsiElement): String =
        when (e) {
            is PsiClass -> {
                e.qualifiedName ?: e.name.orEmpty()
            }

            is PsiMember -> {
                e.containingClass
                    ?.qualifiedName
                    ?.plus(".")
                    ?.plus(e.name) ?: e.name.orEmpty()
            }

            else -> {
                qualifiedKotlinName(e)
            }
        }

    private fun dependency(
        from: SourceEntityId,
        target: SourceTarget,
        element: PsiElement,
        stamp: Long,
        resolved: PsiElement? = null,
        offset: Int = element.textRange.startOffset,
        length: Int = element.textLength,
    ): SourceDependency {
        val kind =
            when {
                PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java) != null ||
                    PsiTreeUtil.getParentOfType(element, KtAnnotationEntry::class.java) != null -> {
                    SourceDependencyKind.ANNOTATION
                }

                resolved is PsiMethod && resolved.isConstructor -> {
                    SourceDependencyKind.CONSTRUCTOR
                }

                resolved is PsiMethod || resolved is KtNamedFunction -> {
                    SourceDependencyKind.CALL
                }

                (
                    resolved is PsiClass &&
                        PsiTreeUtil
                            .getParentOfType(element, PsiNewExpression::class.java)
                            ?.classReference
                            ?.let { reference -> reference === element || PsiTreeUtil.isAncestor(reference, element, false) } == true
                ) ||
                    (
                        (resolved is PsiClass || resolved is KtClassOrObject) &&
                            PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java)?.calleeExpression === element
                    ) -> {
                    SourceDependencyKind.CONSTRUCTOR
                }

                PsiTreeUtil.getParentOfType(element, PsiReferenceList::class.java) != null ||
                    PsiTreeUtil.getParentOfType(element, KtSuperTypeListEntry::class.java) != null -> {
                    SourceDependencyKind.INHERITANCE
                }

                element is PsiJavaCodeReferenceElement ||
                    PsiTreeUtil.getParentOfType(element, KtTypeReference::class.java) != null -> {
                    SourceDependencyKind.TYPE
                }

                resolved is PsiField || resolved is KtProperty -> {
                    SourceDependencyKind.ACCESS
                }

                PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java) != null -> {
                    SourceDependencyKind.CALL
                }

                else -> {
                    SourceDependencyKind.ACCESS
                }
            }
        return SourceDependency(from, target, kind, SourceReference(from.fileUrl, offset, length, stamp))
    }
}

internal fun findUniqueTypeTokenOffset(
    text: String,
    token: String,
): Int? {
    if (token.isEmpty()) return null
    var match: Int? = null
    var from = 0
    while (true) {
        val index = text.indexOf(token, from)
        if (index < 0) return match
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + token.length)
        if ((before == null || !Character.isJavaIdentifierPart(before)) &&
            (after == null || !Character.isJavaIdentifierPart(after))
        ) {
            if (match != null) return null
            match = index
        }
        from = index + 1
    }
}
