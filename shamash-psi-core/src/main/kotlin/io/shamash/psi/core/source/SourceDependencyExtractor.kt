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
import java.util.concurrent.CancellationException
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference

/** Resolves actual Java/Kotlin PSI references; never infers dependencies from package/import text. */
class SourceDependencyExtractor(private val sourceScope: (PsiFile) -> SourceLocation?) {
    data class SourceLocation(val moduleId: String, val sourceRoot: String, val fileUrl: String)

    data class FileResult(
        val entities: List<SourceEntity>,
        val references: List<SourceDependency>,
        val diagnostics: List<SourceDiagnostic>,
    )

    fun extract(file: PsiFile, location: SourceLocation): FileResult {
        ProgressManager.checkCanceled()
        val entities = LinkedHashMap<SourceEntityId, SourceEntity>()
        val refs = ArrayList<SourceDependency>()
        val diagnostics = ArrayList<SourceDiagnostic>()
        data class TypeUse(val owner: PsiElement, val type: PsiType, val evidence: PsiElement)
        val typeUses = ArrayList<TypeUse>()
        val stamp = file.viewProvider.document?.modificationStamp ?: file.modificationStamp

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                entity(element, location)?.let { entities[it.id] = it }
                when (element) {
                    is PsiVariable -> element.typeElement?.let { typeElement ->
                        typeUses += TypeUse(element, element.type, typeElement)
                    }
                    is PsiMethod -> element.returnType?.let { type ->
                        element.returnTypeElement?.let { typeElement ->
                            typeUses += TypeUse(element, type, typeElement)
                        }
                    }
                }
                if (supportedReference(element) && !insideImport(element)) {
                    val owner = owningDeclaration(element) ?: return super.visitElement(element)
                    val from = entity(owner, location) ?: return super.visitElement(element)
                    try {
                        val candidates = element.references
                        if (candidates.isEmpty()) {
                            if (element is KtNameReferenceExpression) {
                                diagnostics += SourceDiagnostic(
                                    location.fileUrl,
                                    "resolve",
                                    "No reference for '${element.text}' at ${element.textOffset}",
                                )
                                refs += dependency(from.id, SourceTarget.Unresolved(element.text), element, stamp)
                            }
                        } else {
                            for (reference in candidates) {
                                ProgressManager.checkCanceled()
                                val resolved = reference.resolve()
                                if (resolved != null && declarationOf(resolved) == null) continue
                                val targets = resolveTargets(resolved)
                                if (targets.isEmpty()) {
                                    diagnostics += SourceDiagnostic(
                                        location.fileUrl,
                                        "resolve",
                                        "Cannot resolve '${element.text}' at ${element.textOffset}",
                                    )
                                    refs += dependency(from.id, SourceTarget.Unresolved(element.text), element, stamp)
                                    continue
                                }
                                val offset = element.textRange.startOffset + reference.rangeInElement.startOffset
                                val length = reference.rangeInElement.length
                                for (target in targets) {
                                    if (target is SourceTarget.Internal && target.entity == from.id) continue
                                    refs += dependency(from.id, target, element, stamp, resolved, offset, length)
                                }
                            }
                        }
                    } catch (c: ProcessCanceledException) {
                        throw c
                    } catch (c: CancellationException) {
                        throw ProcessCanceledException()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw ProcessCanceledException()
                    } catch (t: Exception) {
                        diagnostics += SourceDiagnostic(
                            location.fileUrl,
                            "resolve",
                            "${t.javaClass.simpleName}: ${t.message ?: "resolution failed"}",
                        )
                        refs += dependency(from.id, SourceTarget.Unresolved(element.text), element, stamp)
                    }
                }
                super.visitElement(element)
            }
        })

        // The generic walker covers wildcard/type-variable bounds which are easy to lose in a
        // class-only UAST projection. Ordinary PSI references win because they carry exact token ranges.
        for (use in typeUses) {
            ProgressManager.checkCanceled()
            val owner =
                (use.owner as? PsiField)
                    ?: (use.owner as? PsiMethod)
                    ?: owningDeclaration(use.owner)
                    ?: continue
            val source = entity(owner, location) ?: continue
            val walked = PsiTypeClassReferences.collect(use.type)
            if (walked.truncated) {
                diagnostics += SourceDiagnostic(
                    location.fileUrl, "type", "Generic type nesting exceeded supported depth",
                )
            }
            for (resolvedClass in walked.classes) {
                val target = resolveTargets(resolvedClass).firstOrNull() ?: continue
                if (target is SourceTarget.Internal && target.entity == source.id) continue
                if (refs.any { it.from == source.id && it.target == target && it.kind == SourceDependencyKind.TYPE &&
                        it.evidence.offset in use.evidence.textRange.startOffset..use.evidence.textRange.endOffset
                    }) continue
                val token = resolvedClass.name ?: continue
                val index = use.evidence.text.indexOf(token)
                if (index < 0) {
                    diagnostics += SourceDiagnostic(
                        location.fileUrl, "type", "Cannot locate generic type witness for $token",
                    )
                    continue
                }
                refs +=
                    SourceDependency(
                        source.id, target, SourceDependencyKind.TYPE,
                        SourceReference(location.fileUrl, use.evidence.textOffset + index, token.length, stamp),
                    )
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
        if (declaration is KtTypeAlias) {
            val ref = declaration.getTypeReference() ?: return listOf(SourceTarget.Unresolved(declaration.name.orEmpty()))
            val names = PsiTreeUtil.findChildrenOfType(ref, KtNameReferenceExpression::class.java)
            if (names.isEmpty()) return listOf(SourceTarget.Unresolved(declaration.name.orEmpty()))
            return names.flatMap { name ->
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
            listOfNotNull(entity(declaration, location)?.let { SourceTarget.Internal(it.id) })
        }
    }

    private fun declarationOf(element: PsiElement): PsiElement? {
        val nav = element.navigationElement.takeIf { it !== element && it.containingFile != null } ?: element
        return when (nav) {
            is PsiClass, is PsiMethod, is PsiField,
            is KtClassOrObject, is KtNamedFunction, is KtProperty, is KtTypeAlias -> nav
            else -> {
                // Kotlin light elements may navigate to a name identifier rather than the declaration.
                var parent: PsiElement? = nav.parent
                while (parent != null && parent !is PsiFile) {
                    if (parent is KtClassOrObject || parent is KtNamedFunction ||
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
            e is KtNameReferenceExpression

    private fun insideImport(e: PsiElement): Boolean {
        var parent: PsiElement? = e.parent
        while (parent != null && parent !is PsiFile) {
            if (parent is PsiImportStatementBase || parent is KtImportDirective) return true
            parent = parent.parent
        }
        return false
    }

    private fun owningDeclaration(e: PsiElement): PsiElement? {
        var parent: PsiElement? = e.parent
        var fallback: PsiElement? = null
        while (parent != null && parent !is PsiFile) {
            if (parent is PsiClass || parent is KtClassOrObject) {
                if (fallback == null) fallback = parent
            }
            if (parent is PsiMethod || parent is PsiField ||
                parent is KtNamedFunction || parent is KtTypeAlias
            ) {
                return parent
            }
            if (parent is KtProperty) {
                if (PsiTreeUtil.getParentOfType(parent, KtNamedFunction::class.java) == null) return parent
            }
            parent = parent.parent
        }
        return fallback
    }

    private fun entity(element: PsiElement, location: SourceLocation): SourceEntity? {
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
                if (element.name == null) return null // Anonymous objects are not named declarations.
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
                if (PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java) != null) return null
                name = element.name ?: return null
                kind = SourceEntityKind.FUNCTION
                val receiver = element.receiverTypeReference?.text?.let { "$it." }.orEmpty()
                signature = receiver + qualifiedKotlinName(element) + "(" +
                    element.valueParameters.joinToString(",") { it.typeReference?.text.orEmpty() } + ")"
            }
            is KtProperty -> {
                if (PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java) != null) return null
                name = element.name ?: return null
                kind = SourceEntityKind.PROPERTY
                signature = element.receiverTypeReference?.text?.let { "$it." }.orEmpty() + qualifiedKotlinName(element)
            }
            is KtTypeAlias -> {
                name = element.name ?: return null
                kind = SourceEntityKind.TYPE_ALIAS
                signature = qualifiedKotlinName(element)
            }
            else -> return null
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
            is PsiClass -> e.qualifiedName ?: e.name.orEmpty()
            is PsiMember -> e.containingClass?.qualifiedName?.plus(".")?.plus(e.name) ?: e.name.orEmpty()
            else -> qualifiedKotlinName(e)
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
                    PsiTreeUtil.getParentOfType(element, KtAnnotationEntry::class.java) != null ->
                    SourceDependencyKind.ANNOTATION
                PsiTreeUtil.getParentOfType(element, PsiNewExpression::class.java) != null ||
                    (PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java) != null &&
                        (resolved is PsiClass || resolved is KtClassOrObject)) ->
                    SourceDependencyKind.CONSTRUCTOR
                PsiTreeUtil.getParentOfType(element, PsiReferenceList::class.java) != null ||
                    PsiTreeUtil.getParentOfType(element, KtSuperTypeListEntry::class.java) != null ->
                    SourceDependencyKind.INHERITANCE
                element is PsiJavaCodeReferenceElement ||
                    PsiTreeUtil.getParentOfType(element, KtTypeReference::class.java) != null ->
                    SourceDependencyKind.TYPE
                resolved is PsiField || resolved is KtProperty -> SourceDependencyKind.ACCESS
                resolved is PsiMethod || resolved is KtNamedFunction ||
                    PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java) != null ->
                    SourceDependencyKind.CALL
                else -> SourceDependencyKind.ACCESS
            }
        return SourceDependency(from, target, kind, SourceReference(from.fileUrl, offset, length, stamp))
    }
}
