package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import io.shamash.psi.settings.ShamashSettings

class SetDetectedRootPackageFix(
    private val detectedRoot: String
) : LocalQuickFix {

    override fun getName(): String =
        "Set '$detectedRoot' as Shamash root package"

    override fun getFamilyName(): String =
        "Shamash configuration"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        ShamashSettings.getInstance()
            .state
            .rootPackage = detectedRoot
    }
}
