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
package io.shamash.intellij.plugin.asm.ui.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.shamash.asm.core.scan.ScanResult
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Session-only ASM results. Listeners run on the updating thread; tab refreshes are dispatched to the EDT.
 */
@Service(Service.Level.PROJECT)
class ShamashAsmUiStateService(
    private val project: Project,
) {
    data class AsmUiState(
        val configPath: Path?,
        val scanResult: ScanResult?,
        val updatedAt: Instant,
    )

    fun interface Listener {
        fun onAsmStateChanged(state: AsmUiState?)
    }

    private val stateRef = AtomicReference<AsmUiState?>(null)
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun getState(): AsmUiState? = stateRef.get()

    fun getScanResult(): ScanResult? = stateRef.get()?.scanResult

    fun getConfigPath(): Path? = stateRef.get()?.configPath

    fun clear() {
        updateInternal(null)
    }

    fun update(
        configPath: Path?,
        scanResult: ScanResult?,
    ) {
        updateInternal(
            AsmUiState(
                configPath = configPath,
                scanResult = scanResult,
                updatedAt = Instant.now(),
            ),
        )
    }

    /**
     * Register a listener and automatically unregister it when [disposable] is disposed.
     */
    fun addListener(
        disposable: com.intellij.openapi.Disposable,
        listener: Listener,
    ) {
        listeners.add(listener)
        Disposer.register(disposable) { listeners.remove(listener) }
    }

    private fun updateInternal(newState: AsmUiState?) {
        stateRef.set(newState)
        fireListeners(newState)
        refreshToolWindow(newState)
    }

    private fun fireListeners(newState: AsmUiState?) {
        for (l in listeners) {
            runCatching { l.onAsmStateChanged(newState) }
        }
    }

    private fun refreshToolWindow(newState: AsmUiState?) {
        if (project.isDisposed) return

        val app = ApplicationManager.getApplication()
        val runnable =
            Runnable {
                if (project.isDisposed) return@Runnable

                val controller =
                    project.getService(ShamashAsmToolWindowController::class.java)
                        ?: return@Runnable

                controller.refreshAll()

                val result = newState?.scanResult
                if (result != null) {
                    when {
                        result.hasConfigErrors -> controller.select(ShamashAsmToolWindowController.Tab.CONFIG)
                        result.hasEngineResult -> controller.select(ShamashAsmToolWindowController.Tab.FINDINGS)
                        else -> controller.select(ShamashAsmToolWindowController.Tab.DASHBOARD)
                    }
                }
            }

        if (app.isDispatchThread) {
            runnable.run()
        } else {
            app.invokeLater(runnable)
        }
    }

    companion object {
        fun getInstance(project: Project): ShamashAsmUiStateService = project.getService(ShamashAsmUiStateService::class.java)
    }
}
