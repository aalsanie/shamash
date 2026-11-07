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
package io.shamash.intellij.plugin.analysis

import com.intellij.ProjectTopics
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.config.ValidationError
import io.shamash.psi.core.engine.EngineError
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * One project-scoped source analysis queue. The backend is replaceable by the configuration-free
 * source-facts extractor without changing ownership, cancellation, diagnostics or presentation.
 */
@Service(Service.Level.PROJECT)
class IdeAnalysisCoordinator(
    private val project: Project,
) : Disposable {
    data class SourcePresentation(
        val findings: List<Finding> = emptyList(),
        val validationErrors: List<ValidationError> = emptyList(),
        val engineErrors: List<EngineError> = emptyList(),
    )

    fun interface Backend {
        fun analyze(
            request: AnalysisRequest,
            indicator: ProgressIndicator,
        ): AnalysisOutcome<SourcePresentation>
    }

    private val lifecycle = AnalysisLifecycle<SourcePresentation>()
    private val executor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Shamash source analysis", 1)
    private val callbacks = CopyOnWriteArrayList<(IdeAnalysisState<SourcePresentation>) -> Unit>()
    private val gate = Any()
    private var activeTask: Future<*>? = null
    private var indicator: ProgressIndicatorBase? = null

    @Volatile
    private var disposed = false

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (disposed || project.isDisposed) return
                    val psi = PsiDocumentManager.getInstance(project).getPsiFile(event.document) ?: return
                    val virtualFile = psi.virtualFile ?: return
                    if (ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) sourceChanged()
                }
            },
            this,
        )
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ProjectTopics.PROJECT_ROOTS,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) = this@IdeAnalysisCoordinator.rootsChanged()
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (disposed || project.isDisposed) return
                    val contentRoots =
                        ProjectRootManager.getInstance(project).contentRoots.map { it.path.trimEnd('/') + "/" }
                    if (events.any { change ->
                            val path = change.path.replace('\\', '/')
                            val previousNameWasSource =
                                change is VFilePropertyChangeEvent &&
                                    change.propertyName == VirtualFile.PROP_NAME &&
                                    (change.oldValue as? String)?.let {
                                        it.endsWith(".java") || it.endsWith(".kt")
                                    } == true
                            val sourceFile = path.endsWith(".java") || path.endsWith(".kt") || previousNameWasSource
                            val insideProject =
                                contentRoots.any { root -> path.startsWith(root) } ||
                                    (
                                        change is VFileMoveEvent &&
                                            contentRoots.any { root ->
                                                (change.oldParent.path.trimEnd('/') + "/").startsWith(root)
                                            }
                                    )
                            sourceFile && insideProject
                        }
                    ) {
                        sourceChanged()
                    }
                }
            },
        )
    }

    fun state(): IdeAnalysisState<SourcePresentation> = lifecycle.snapshot()

    fun subscribe(
        owner: Disposable,
        callback: (IdeAnalysisState<SourcePresentation>) -> Unit,
    ) {
        if (disposed || Disposer.isDisposed(owner)) return
        callbacks.add(callback)
        Disposer.register(owner) { callbacks.remove(callback) }
        publishState()
    }

    fun analyze(backend: Backend): AnalysisRequest? {
        if (disposed || project.isDisposed) return null
        val request =
            synchronized(gate) {
                if (disposed || project.isDisposed) return null
                cancelWork()
                lifecycle.begin()
            } ?: return null
        publishState()
        // runWhenSmart is asynchronous on the EDT: no waiting for indexing or a read lock here.
        DumbService.getInstance(project).runWhenSmart {
            if (disposed || project.isDisposed || !lifecycle.isCurrent(request)) return@runWhenSmart
            synchronized(gate) {
                if (disposed || project.isDisposed || !lifecycle.isCurrent(request)) return@synchronized
                val progress = ProgressIndicatorBase()
                indicator = progress
                activeTask =
                    executor.submit {
                        if (!lifecycle.advance(request, AnalysisPhase.EXTRACTING)) return@submit
                        publishState()
                        try {
                            progress.checkCanceled()
                            val outcome =
                                ProgressManager.getInstance().runProcess(
                                    Computable { backend.analyze(request, progress) },
                                    progress,
                                )
                            progress.checkCanceled()
                            if (lifecycle.publish(request, outcome)) {
                                publishLegacySnapshot()
                                publishState()
                            }
                        } catch (_: ProcessCanceledException) {
                            cancelRequest(request)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            cancelRequest(request)
                        } catch (failure: Throwable) {
                            if (lifecycle.fail(request, failure.message ?: failure.javaClass.simpleName)) publishState()
                        } finally {
                            synchronized(gate) {
                                if (indicator === progress) {
                                    indicator = null
                                    activeTask = null
                                }
                            }
                        }
                    }
            }
        }
        return request
    }

    /** Called by graph-producing backends between extraction and off-read-lock graph work. */
    fun graphStarted(
        request: AnalysisRequest,
        progress: Double? = null,
    ): Boolean {
        val moved = lifecycle.advance(request, AnalysisPhase.BUILDING_GRAPH, progress)
        if (moved) publishState()
        return moved
    }

    fun cancel() {
        synchronized(gate) {
            if (lifecycle.cancel()) cancelWork()
        }
        publishState()
    }

    private fun cancelRequest(request: AnalysisRequest) {
        val cancelled =
            synchronized(gate) {
                if (!lifecycle.isCurrent(request) || !lifecycle.cancel()) {
                    false
                } else {
                    cancelWork()
                    true
                }
            }
        if (cancelled) publishState()
    }

    private fun sourceChanged() {
        synchronized(gate) {
            lifecycle.sourceChanged()
            cancelWork()
        }
        publishState()
    }

    private fun rootsChanged() {
        synchronized(gate) {
            lifecycle.rootsChanged()
            cancelWork()
        }
        publishState()
    }

    private fun cancelWork() {
        indicator?.cancel()
        activeTask?.cancel(true)
        indicator = null
        activeTask = null
    }

    /** The legacy dashboard is an interim adapter; its contents are never treated as source graph results. */
    private fun publishLegacySnapshot() {
        val state = lifecycle.snapshot()
        val data = state.visibleResult?.payload ?: return
        if (state.resultRelation != ResultRelation.CURRENT) return
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed || lifecycle.snapshot() !== state) {
                return@invokeLater
            }
            val ui =
                io.shamash.intellij.plugin.psi.ui.actions.ShamashPsiUiStateService
                    .getInstance(project)
            ui.updateFromScan(data.findings, data.validationErrors, null, null)
            // Views subscribe with their own Disposable. Never contact a possibly disposed
            // tool-window content or activate it as a side effect of completion.
        }
    }

    private fun publishState() {
        if (disposed || project.isDisposed) return
        val current = lifecycle.snapshot()
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            if (lifecycle.snapshot() !== current) return@invokeLater
            for (listener in callbacks) listener(current)
        }
    }

    override fun dispose() {
        synchronized(gate) {
            disposed = true
            lifecycle.dispose()
            cancelWork()
            executor.shutdownNow()
            callbacks.clear()
        }
    }

    companion object {
        fun getInstance(project: Project): IdeAnalysisCoordinator = project.getService(IdeAnalysisCoordinator::class.java)
    }
}
