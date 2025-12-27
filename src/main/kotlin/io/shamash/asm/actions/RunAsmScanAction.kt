/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.asm.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.shamash.asm.service.AsmIndexService

/**
 * Dev/debug action: build the ASM index and emit a short summary.
 */
class RunAsmScanAction : AnAction("Shamash: Run Scan") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        object : Task.Backgroundable(project, "Shamash scan", false) {
            override fun run(indicator: ProgressIndicator) {
                val index = AsmIndexService.getInstance(project).rescan(indicator)

                val total = index.classes.size
                val deps = index.references.values.sumOf { it.size }

                if (total == 0 && deps == 0) {
                    notify(
                        project,
                        "ASM scan found no bytecode",
                        "No compiled class files were found. Build the project (Build → Build Project), then run the ASM scan again.",
                    )
                    return
                }

                notify(
                    project,
                    "ASM scan complete",
                    "Indexed $total classes. Captured $deps bytecode references.",
                )
            }
        }.queue()
    }

    private fun notify(
        project: com.intellij.openapi.project.Project,
        title: String,
        content: String,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Shamash")
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
