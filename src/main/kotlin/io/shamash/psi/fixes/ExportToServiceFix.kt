package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.util.PackageUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.architecture.Layer
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.refactor.TargetPackageResolver

class ExportToServiceFix : LocalQuickFix {

    override fun getName(): String = "Export method to Service"
    override fun getFamilyName(): String = "Shamash architecture fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = resolveMethod(descriptor) ?: return
        if (!method.isValid || method.isConstructor) return
        if (!method.hasModifierProperty(PsiModifier.PRIVATE)) return

        val containingClass = method.containingClass ?: return
        val javaFile = method.containingFile as? PsiJavaFile ?: return

        // Only meaningful for forbidden-layer private methods.
        val layer = LayerDetector.detect(containingClass)
        if (layer !in setOf(Layer.CONTROLLER, Layer.SERVICE, Layer.DAO)) return

        val root = TargetPackageResolver.resolveRoot(javaFile) ?: return
        val servicePkg = TargetPackageResolver.resolveLayerPackage(root, Layer.SERVICE)

        WriteCommandAction.writeCommandAction(project)
            .withName("Shamash: Export ${method.name} to service")
            .run<RuntimeException> {

                // Safety: do not export if it captures instance state from containing class.
                if (capturesContainingInstanceState(method, containingClass)) return@run

                val serviceClassName = guessServiceClassName(containingClass.name ?: "Service")
                val serviceFqn = "$servicePkg.$serviceClassName"

                val targetDir = findOrCreatePackageDir(project, javaFile, servicePkg) ?: return@run

                val serviceClass = findOrCreateServiceClass(project, targetDir, serviceFqn, serviceClassName)
                    ?: return@run

                val serviceFieldName = ensureServiceField(containingClass, serviceClass)

                ensureServiceMethod(serviceClass, method)

                rewriteLocalCallSitesToService(containingClass, method, serviceFieldName)

                // Finally delete original method (rule satisfied).
                method.delete()
            }
    }

    private fun resolveMethod(descriptor: ProblemDescriptor): PsiMethod? {
        val el = descriptor.psiElement ?: return null
        return when (el) {
            is PsiMethod -> el
            else -> el.parent as? PsiMethod
        }
    }

    private fun capturesContainingInstanceState(method: PsiMethod, containingClass: PsiClass): Boolean {
        val body = method.body ?: return false

        // If it references non-static fields/methods from the containing class, it is not safe to export.
        val refs = PsiTreeUtil.collectElementsOfType(body, PsiReferenceExpression::class.java)
        for (ref in refs) {
            val resolved = ref.resolve() ?: continue

            when (resolved) {
                is PsiField -> {
                    if (resolved.containingClass == containingClass &&
                        !resolved.hasModifierProperty(PsiModifier.STATIC)
                    ) return true
                }

                is PsiMethod -> {
                    if (resolved == method) continue
                    if (resolved.containingClass == containingClass &&
                        !resolved.hasModifierProperty(PsiModifier.STATIC)
                    ) return true
                }
            }
        }

        // Also reject explicit "this" usage (usually indicates instance capture).
        val thisExpr = PsiTreeUtil.collectElementsOfType(body, PsiThisExpression::class.java)
        if (thisExpr.isNotEmpty()) return true

        return false
    }

    private fun guessServiceClassName(containingName: String): String {
        // Controller -> Service name mapping (best effort, deterministic)
        val base = containingName
            .removeSuffix("Controller")
            .removeSuffix("Resource")
            .removeSuffix("Endpoint")
            .ifBlank { containingName }
        return if (base.endsWith("Service")) base else "${base}Service"
    }

    private fun findOrCreatePackageDir(
        project: Project,
        file: PsiJavaFile,
        targetPackageFqn: String
    ): PsiDirectory? {
        val vFile = file.virtualFile ?: return null
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val sourceRoot = fileIndex.getSourceRootForFile(vFile) ?: return null

        val psiManager = PsiManager.getInstance(project)
        val sourceRootDir = psiManager.findDirectory(sourceRoot) ?: return null

        return PackageUtil.findOrCreateDirectoryForPackage(
            project,
            targetPackageFqn,
            sourceRootDir,
            /* askUserToCreate = */ true
        )
    }

    private fun findOrCreateServiceClass(
        project: Project,
        targetDir: PsiDirectory,
        serviceFqn: String,
        serviceClassName: String
    ): PsiClass? {
        val facade = JavaPsiFacade.getInstance(project)
        val existing = facade.findClass(serviceFqn, GlobalSearchScope.projectScope(project))
        if (existing != null && existing.isValid) return existing

        return JavaDirectoryService.getInstance().createClass(targetDir, serviceClassName)
    }

    private fun ensureServiceField(containingClass: PsiClass, serviceClass: PsiClass): String {
        // Reuse if already present
        val existing = containingClass.fields.firstOrNull { field ->
            val type = field.type
            type is PsiClassType && type.resolve() == serviceClass
        }
        if (existing != null) return existing.name

        val serviceClassName = serviceClass.name ?: return "service"
        val fieldName = decapitalize(serviceClassName)

        val factory = JavaPsiFacade.getElementFactory(containingClass.project)
        val fieldText = "private final $serviceClassName $fieldName = new $serviceClassName();"
        val newField = factory.createFieldFromText(fieldText, containingClass)

        // Insert near top of class body (after opening brace)
        val anchor = containingClass.lBrace
        if (anchor != null) {
            containingClass.addAfter(newField, anchor)
        } else {
            containingClass.add(newField)
        }

        return fieldName
    }

    private fun ensureServiceMethod(serviceClass: PsiClass, original: PsiMethod) {
        // Avoid duplicates by signature name+param types.
        val already = serviceClass.methods.any { m ->
            m.name == original.name && m.parameterList.parametersCount == original.parameterList.parametersCount &&
                    m.parameterList.parameters.zip(original.parameterList.parameters).all { (a, b) ->
                        a.type.presentableText == b.type.presentableText
                    }
        }
        if (already) return

        val copied = original.copy() as PsiMethod
        val mods = copied.modifierList
        mods.setModifierProperty(PsiModifier.PRIVATE, false)
        mods.setModifierProperty(PsiModifier.PROTECTED, false)
        mods.setModifierProperty(PsiModifier.PUBLIC, true)

        serviceClass.add(copied)
    }

    private fun rewriteLocalCallSitesToService(
        containingClass: PsiClass,
        method: PsiMethod,
        serviceFieldName: String
    ) {
        val factory = JavaPsiFacade.getElementFactory(containingClass.project)

        // Private method should only be referenced within same class, but we handle whatever we find.
        val refs = ReferencesSearch.search(method).findAll()
        for (ref in refs) {
            val call = PsiTreeUtil.getParentOfType(ref.element, PsiMethodCallExpression::class.java) ?: continue

            // Replace "foo(a,b)" with "service.foo(a,b)"
            val argsText = call.argumentList.text // "(a, b)"
            val newExprText = "$serviceFieldName.${method.name}$argsText"
            val newExpr = factory.createExpressionFromText(newExprText, call)

            call.replace(newExpr)
        }
    }

    private fun decapitalize(s: String): String {
        if (s.isBlank()) return "service"
        val c0 = s[0]
        val lower0 = c0.lowercaseChar()
        return if (c0 == lower0) s else lower0 + s.substring(1)
    }
}
