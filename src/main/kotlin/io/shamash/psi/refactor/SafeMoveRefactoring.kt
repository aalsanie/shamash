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
     * - Can create the target package directory under the file's source root
     */
    fun moveToPackage(
        project: Project,
        file: PsiJavaFile,
        targetPackageFqn: String
    ) {
        val targetDir = findOrCreatePackageDir(project, file, targetPackageFqn) ?: return

        WriteCommandAction.writeCommandAction(project)
            .withName("Shamash: Move to $targetPackageFqn")
            .run<RuntimeException> {
                MoveFilesOrDirectoriesProcessor(
                    project,
                    arrayOf(file),
                    targetDir,
                    /* searchInComments = */ true,
                    /* searchInNonJavaFiles = */ true,
                    /* moveCallback = */ null,
                    /* prepareSuccessfulCallback = */ null
                ).run()
            }
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

        // Creates if missing
        return PackageUtil.findOrCreateDirectoryForPackage(
            project,
            targetPackageFqn,
            sourceRootDir,
            /* askUserToCreate = */ true
        )
    }
}
