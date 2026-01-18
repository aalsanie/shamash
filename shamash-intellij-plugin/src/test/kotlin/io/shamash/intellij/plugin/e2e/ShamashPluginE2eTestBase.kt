/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.intellij.plugin.e2e

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Files
import java.nio.file.Path

abstract class ShamashPluginE2eTestBase : BasePlatformTestCase() {
    protected fun ensureMainResourcesRoot(): VirtualFile {
        val dir = myFixture.tempDirFixture.findOrCreateDir("src/main/resources")
        // Register as a source root so ResourceBaseLookup/AsmResourceBaseLookup can see it.
        PsiTestUtil.addSourceRoot(module, dir)
        return dir
    }

    protected fun writeFile(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    protected fun refreshAndFind(path: Path): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

    protected fun fire(action: AnAction) {
        runInEdtAndWait {
            action.actionPerformed(testEvent(action))
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    protected fun testEvent(action: AnAction): AnActionEvent {
        val dc: DataContext =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .build()
        return AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dc)
    }
}
