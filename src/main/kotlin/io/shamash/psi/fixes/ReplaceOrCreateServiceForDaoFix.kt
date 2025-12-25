package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.util.PackageUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.architecture.Layer
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.refactor.TargetPackageResolver

class ReplaceOrCreateServiceForDaoFix : LocalQuickFix {

    override fun getName(): String = "Replace DAO dependency with Service (create if missing)"
    override fun getFamilyName(): String = "Shamash architecture fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val controller = findContainingClass(descriptor) ?: return
        val file = controller.containingFile as? PsiJavaFile ?: return
        if (!file.isValid) return

        WriteCommandAction.writeCommandAction(project)
            .withName("Shamash: Replace DAO with Service")
            .run<RuntimeException> {
                val daoDeps = ControllerDaoDeps.findDaoDependencies(controller)
                if (daoDeps.isEmpty()) return@run

                val root = TargetPackageResolver.resolveRoot(file) ?: return@run
                val servicePkg = TargetPackageResolver.resolveLayerPackage(root, Layer.SERVICE)

                daoDeps.forEach { dep ->
                    val daoClass = dep.daoClass ?: return@forEach

                    val serviceName = Naming.serviceNameForDaoOrController(daoClass, controller)

                    val serviceClass = ServiceFactory.findOrCreateService(
                        project = project,
                        anchorFile = file,
                        servicePackage = servicePkg,
                        serviceName = serviceName,
                        daoClass = daoClass
                    ) ?: return@forEach

                    // Generate delegation methods required by controller usage.
                    val requiredCalls = ControllerDaoDeps.collectDaoCalls(controller, dep)
                    ServiceFactory.ensureDelegationMethods(serviceClass, daoClass, requiredCalls)

                    // Rewrite controller injection points + usages.
                    ControllerDaoDeps.replaceDaoWithService(controller, dep, serviceClass, serviceName)
                }
            }
    }

    private fun findContainingClass(descriptor: ProblemDescriptor): PsiClass? {
        val e = descriptor.psiElement ?: return null
        return PsiTreeUtil.getParentOfType(e, PsiClass::class.java, false)
            ?: (e as? PsiClass)
    }

    /**
     * Everything below is intentionally bundled inside the fix so the inspection stays untouched
     * and one-liner simple.
     */
    private object ControllerDaoDeps {

        data class DaoDependency(
            val daoClass: PsiClass?,
            val field: PsiField? = null,
            val ctorParam: PsiParameter? = null
        )

        data class DaoCall(
            val methodName: String,
            val resolvedMethod: PsiMethod?,
            val argumentNames: List<String>,
            val argumentTypes: List<PsiType>
        )

        fun findDaoDependencies(controller: PsiClass): List<DaoDependency> {
            val result = mutableListOf<DaoDependency>()

            // 1) Fields typed as DAO.
            controller.fields.forEach { f ->
                val dao = (f.type as? PsiClassType)?.resolve()
                if (dao != null && LayerDetector.detect(dao) == Layer.DAO) {
                    result += DaoDependency(daoClass = dao, field = f)
                }
            }

            // 2) Constructor parameters typed as DAO.
            controller.constructors.forEach { ctor ->
                ctor.parameterList.parameters.forEach { p ->
                    val dao = (p.type as? PsiClassType)?.resolve()
                    if (dao != null && LayerDetector.detect(dao) == Layer.DAO) {
                        result += DaoDependency(daoClass = dao, ctorParam = p)
                    }
                }
            }

            return result.distinctBy { it.daoClass?.qualifiedName + "|" + (it.field?.name ?: "") + "|" + (it.ctorParam?.name ?: "") }
        }

        fun collectDaoCalls(controller: PsiClass, dep: DaoDependency): List<DaoCall> {
            val daoVarName = dep.field?.name ?: dep.ctorParam?.name ?: return emptyList()

            val calls = PsiTreeUtil.collectElementsOfType(controller, PsiMethodCallExpression::class.java)
                .filter { call ->
                    val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression
                    qualifier?.referenceName == daoVarName
                }

            return calls.map { call ->
                val resolved = call.resolveMethod()
                val args = call.argumentList.expressions

                DaoCall(
                    methodName = call.methodExpression.referenceName ?: return@map null,
                    resolvedMethod = resolved,
                    argumentNames = args.mapIndexed { idx, _ -> "arg$idx" },
                    argumentTypes = args.map { it.type ?: PsiType.getJavaLangObject(controller.manager, controller.resolveScope) }
                )
            }.filterNotNull()
                .distinctBy { it.methodName + "|" + it.argumentTypes.joinToString(",") { t -> t.canonicalText } }
        }

        fun replaceDaoWithService(
            controller: PsiClass,
            dep: DaoDependency,
            serviceClass: PsiClass,
            serviceName: String
        ) {
            val factory = JavaPsiFacade.getElementFactory(controller.project)

            // Replace field type
            dep.field?.let { field ->
                val oldName = field.name
                val newType = factory.createType(serviceClass)
                field.typeElement?.replace(factory.createTypeElement(newType))

                // Rename variable in a safe minimal way: dao -> service, but only if it matches DAO-ish name.
                val newVarName = Naming.variableNameForService(serviceName)
                if (oldName != newVarName) {
                    RenamePsi.renameIdentifierIfPossible( field, newVarName)
                }
            }

            // Replace ctor param type
            dep.ctorParam?.let { param ->
                val newType = factory.createType(serviceClass)
                param.typeElement?.replace(factory.createTypeElement(newType))

                val newVarName = Naming.variableNameForService(serviceName)
                if (param.name != newVarName) {
                    RenamePsi.renameIdentifierIfPossible(param, newVarName)
                }
            }

            // Update call qualifiers: if rename didn’t run (or some calls still refer to old name),
            // rewrite daoVar.* -> serviceVar.*
            val desiredVar = Naming.variableNameForService(serviceName)
            val possibleOld = listOfNotNull(dep.field?.name, dep.ctorParam?.name).toSet()

            PsiTreeUtil.collectElementsOfType(controller, PsiReferenceExpression::class.java)
                .filter { it.referenceName in possibleOld }
                .forEach { ref ->
                    ref.handleElementRename(desiredVar)
                }
        }
    }

    private object ServiceFactory {

        fun findOrCreateService(
            project: Project,
            anchorFile: PsiJavaFile,
            servicePackage: String,
            serviceName: String,
            daoClass: PsiClass
        ): PsiClass? {
            val facade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)

            facade.findClass("$servicePackage.$serviceName", scope)?.let { return it }

            val dir = findOrCreatePackageDir(project, anchorFile, servicePackage) ?: return null
            val createdFile = createJavaServiceFile(project, dir, servicePackage, serviceName, daoClass) ?: return null

            return PsiTreeUtil.findChildOfType(createdFile, PsiClass::class.java)
        }

        fun ensureDelegationMethods(
            serviceClass: PsiClass,
            daoClass: PsiClass,
            requiredCalls: List<ControllerDaoDeps.DaoCall>
        ) {
            if (requiredCalls.isEmpty()) return
            val factory = JavaPsiFacade.getElementFactory(serviceClass.project)

            val daoFieldName = Naming.variableNameForDao(daoClass)

            requiredCalls.forEach { call ->
                val signatureKey = signatureKey(call)
                if (hasMethod(serviceClass, signatureKey)) return@forEach

                val resolved = call.resolvedMethod
                val returnType = resolved?.returnType?.canonicalText ?: "void"

                val params = call.argumentTypes.mapIndexed { idx, t ->
                    "${t.canonicalText} ${call.argumentNames.getOrElse(idx) { "arg$idx" }}"
                }.joinToString(", ")

                val argPass = call.argumentNames.joinToString(", ")
                val body = if (returnType == "void") {
                    "{ $daoFieldName.${call.methodName}($argPass); }"
                } else {
                    "{ return $daoFieldName.${call.methodName}($argPass); }"
                }

                val methodText = "public $returnType ${call.methodName}($params) $body"
                val m = factory.createMethodFromText(methodText, serviceClass)

                serviceClass.add(m)
            }
        }

        private fun signatureKey(call: ControllerDaoDeps.DaoCall): String =
            call.methodName + "(" + call.argumentTypes.joinToString(",") { it.canonicalText } + ")"

        private fun hasMethod(serviceClass: PsiClass, signatureKey: String): Boolean {
            return serviceClass.methods.any { m ->
                val k = m.name + "(" + m.parameterList.parameters.joinToString(",") { it.type.canonicalText } + ")"
                k == signatureKey
            }
        }

        private fun createJavaServiceFile(
            project: Project,
            dir: PsiDirectory,
            pkg: String,
            serviceName: String,
            daoClass: PsiClass
        ): PsiJavaFile? {
            val daoFqn = daoClass.qualifiedName ?: return null
            val daoSimple = daoClass.name ?: return null
            val daoVar = Naming.variableNameForDao(daoClass)

            val text = buildString {
                appendLine("package $pkg;")
                appendLine()
                appendLine("import org.springframework.stereotype.Service;")
                appendLine()
                appendLine("@Service")
                appendLine("public class $serviceName {")
                appendLine()
                appendLine("    private final $daoSimple $daoVar;")
                appendLine()
                appendLine("    public $serviceName($daoSimple $daoVar) {")
                appendLine("        this.$daoVar = $daoVar;")
                appendLine("    }")
                appendLine()
                appendLine("}")
            }

            // Ensure DAO import if package differs (we keep it explicit and safe).
            val needsImport = !daoFqn.startsWith("$pkg.")
            val finalText = if (!needsImport) text else {
                text.replace(
                    "import org.springframework.stereotype.Service;",
                    "import org.springframework.stereotype.Service;\nimport $daoFqn;"
                )
            }

            val fileName = "$serviceName.java"
            val psiFile = PsiFileFactory.getInstance(project)
                .createFileFromText(fileName, JavaFileType.INSTANCE, finalText)

            val added = dir.add(psiFile)
            return added as? PsiJavaFile
        }

        private fun findOrCreatePackageDir(
            project: Project,
            file: PsiJavaFile,
            packageFqn: String
        ): PsiDirectory? {
            val vFile = file.virtualFile ?: return null
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            val sourceRoot = fileIndex.getSourceRootForFile(vFile) ?: return null

            val psiManager = PsiManager.getInstance(project)
            val sourceRootDir = psiManager.findDirectory(sourceRoot) ?: return null

            return PackageUtil.findOrCreateDirectoryForPackage(
                project,
                packageFqn,
                sourceRootDir,
                /* askUserToCreate = */ true
            )
        }
    }

    private object Naming {

        fun serviceNameForDaoOrController(dao: PsiClass, controller: PsiClass): String {
            val daoName = dao.name.orEmpty()
            val baseFromDao = when {
                daoName.endsWith("Dao") -> daoName.removeSuffix("Dao")
                daoName.endsWith("Repository") -> daoName.removeSuffix("Repository")
                else -> daoName
            }.ifBlank { daoName }

            // Prefer DAO-derived service name; fallback to controller-derived.
            val controllerName = controller.name.orEmpty()
            val baseFromController = controllerName.removeSuffix("Controller").ifBlank { controllerName }

            val base = baseFromDao.ifBlank { baseFromController }
            return "${base}Service"
        }

        fun variableNameForService(serviceName: String): String =
            serviceName.replaceFirstChar { it.lowercaseChar() }

        fun variableNameForDao(daoClass: PsiClass): String {
            val n = daoClass.name ?: "dao"
            return n.replaceFirstChar { it.lowercaseChar() }
        }
    }

    private object RenamePsi {
        /**
         * Renames a field/parameter identifier with minimal risk.
         * We avoid running heavyweight processors here to keep it stable for 0.1.0.
         */
        fun renameIdentifierIfPossible(element: PsiNamedElement, newName: String) {
            try {
                element.setName(newName)
            } catch (_: Throwable) {
                // If rename is not possible (read-only, etc.), we silently skip and rely on qualifier rewriting.
            }
        }
    }
}
