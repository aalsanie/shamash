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
package io.shamash.asm.ui.dashboard.export

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Paths

object ExportUtil {
    enum class Format(
        val ext: String,
    ) {
        JSON("json"),
        XML("xml"),
    }

    fun saveWithDialog(
        project: Project,
        title: String,
        description: String,
        format: Format,
        suggestedFileName: String,
        content: String,
    ) {
        val descriptor = FileSaverDescriptor(title, description, format.ext)
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)

        // user chooses the location (root base path) TODO: find a better place to save
        val wrapper = dialog.save(Paths.get(""), suggestedFileName) ?: return

        try {
            val file = wrapper.file
            FileUtil.writeToFile(file, content, Charsets.UTF_8)
            notify(project, "Export complete", "Saved to: ${file.absolutePath}", NotificationType.INFORMATION)
        } catch (t: Throwable) {
            notify(project, "Export failed", t.message ?: t::class.java.simpleName, NotificationType.ERROR)
        }
    }

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Shamash")
            .createNotification(title, content, type)
            .notify(project)
    }
}
