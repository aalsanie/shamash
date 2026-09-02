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
package io.shamash.intellij.plugin.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane

@Service(Service.Level.PROJECT)
class ShamashToolWindowNavigator {
    enum class Surface { BUILD, SOURCE }

    private var productTabs: JBTabbedPane? = null

    fun attach(tabs: JBTabbedPane) {
        productTabs = tabs
    }

    fun select(surface: Surface) {
        val tabs = productTabs ?: return
        val index =
            when (surface) {
                Surface.BUILD -> 0
                Surface.SOURCE -> 1
            }
        if (index in 0 until tabs.tabCount) tabs.selectedIndex = index
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ShamashToolWindowNavigator = project.getService(ShamashToolWindowNavigator::class.java)
    }
}
