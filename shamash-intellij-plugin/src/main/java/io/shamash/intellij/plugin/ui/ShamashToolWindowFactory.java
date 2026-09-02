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
package io.shamash.intellij.plugin.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController;
import io.shamash.intellij.plugin.psi.ui.ShamashPsiToolWindowController;
import org.jetbrains.annotations.NotNull;

public final class ShamashToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.getContentManager().removeAllContents(true);

        JBTabbedPane productTabs = new JBTabbedPane();

        JBTabbedPane buildTabs = new JBTabbedPane();
        ShamashAsmToolWindowController asm = ShamashAsmToolWindowController.getInstance(project);
        asm.init(buildTabs);
        asm.refreshAll();
        asm.select(ShamashAsmToolWindowController.Tab.DASHBOARD);
        productTabs.addTab("Build Analysis", buildTabs);

        JBTabbedPane sourceTabs = new JBTabbedPane();
        ShamashPsiToolWindowController psi = ShamashPsiToolWindowController.getInstance(project);
        psi.init(sourceTabs);
        psi.refreshAll();
        psi.select(ShamashPsiToolWindowController.Tab.DASHBOARD);
        productTabs.addTab("Source Analysis", sourceTabs);

        ShamashToolWindowNavigator navigator = ShamashToolWindowNavigator.getInstance(project);
        navigator.attach(productTabs);
        navigator.select(ShamashToolWindowNavigator.Surface.BUILD);

        Content content = ContentFactory.getInstance().createContent(productTabs, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
