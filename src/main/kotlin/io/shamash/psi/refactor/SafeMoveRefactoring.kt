package io.shamash.psi.refactor

import com.intellij.ide.util.PackageUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor

object SafeMoveRefactoring {

    /**
     * Moves a Java file into the given target package using IntelliJ refactoring APIs.
     *
     * - Updates package declaration
     * - Fixes imports and references
     * - Supports undo / redo
     * - Creates the target package directory under the file's source root (if missing)
     *
     * Returns true if a refactoring run was started, false otherwise.
     */
    fun moveToPackage(
        project: Project,
        file: PsiJavaFile,
        targetPackageFqn: String,
        askUserToCreate: Boolean = true
    ): Boolean {
        val targetDir = findOrCreatePackageDir(project, file, targetPackageFqn, askUserToCreate)
            ?: return false

        // Important: don't wrap processors in WriteCommandAction; they own their command/write.
        MoveFilesOrDirectoriesProcessor(
            project,
            arrayOf(file),
            targetDir,
            /* searchInComments = */ true,
            /* searchInNonJavaFiles = */ true,
            /* moveCallback = */ null,
            /* prepareSuccessfulCallback = */ null
        ).run()

        return true
    }

    private fun findOrCreatePackageDir(
        project: Project,
        file: PsiJavaFile,
        targetPackageFqn: String,
        askUserToCreate: Boolean
    ): PsiDirectory? {
        val vFile = file.virtualFile ?: return null
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val sourceRoot = fileIndex.getSourceRootForFile(vFile) ?: return null

        val psiManager = PsiManager.getInstance(project)
        val sourceRootDir = psiManager.findDirectory(sourceRoot) ?: return null

        var targetDir: PsiDirectory? = null

        // Package creation touches PSI/VFS => must be in write command.
        WriteCommandAction.runWriteCommandAction(project) {
            targetDir = PackageUtil.findOrCreateDirectoryForPackage(
                project,
                targetPackageFqn,
                sourceRootDir,
                /* askUserToCreate = */ askUserToCreate
            )
        }

        return targetDir?.takeIf { it.isValid && it.isWritable }
    }
}
