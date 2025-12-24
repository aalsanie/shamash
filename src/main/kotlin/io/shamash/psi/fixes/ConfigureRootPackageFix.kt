package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

class ConfigureRootPackageFix : LocalQuickFix {

    override fun getFamilyName(): String =
        "Configure Shamash root package"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(
                project,
                "Shamash"
            )
    }
}
