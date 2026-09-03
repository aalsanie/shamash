/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.runInEdtAndWait
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState
import io.shamash.intellij.plugin.psi.ui.actions.ShamashPsiUiStateService
import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiSettingsState
import java.nio.file.Files
import java.nio.file.Path

abstract class ShamashPluginE2eTestBase : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    override fun setUp() {
        super.setUp()
        ShamashAsmSettingsState.getInstance(project).loadState(ShamashAsmSettingsState.State())
        ShamashPsiSettingsState.getInstance(project).loadState(ShamashPsiSettingsState.State())
        ShamashAsmUiStateService.getInstance(project).clear()
        ShamashPsiUiStateService.getInstance(project).updateFromScan(emptyList(), emptyList(), null, null)
    }

    protected fun ensureMainResourcesRoot(): VirtualFile {
        val dir = myFixture.tempDirFixture.findOrCreateDir("src/main/resources")
        // Register as a source root so ResourceBaseLookup/AsmResourceBaseLookup can see it.
        PsiTestUtil.addSourceRoot(myModule, dir)
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
            val event =
                AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, SimpleDataContext.getProjectContext(project))
            action.actionPerformed(event)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
}
