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
package io.shamash.asm.ui.dashboard.tabs

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.TableView
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import io.shamash.asm.model.AsmClassInfo
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.model.Finding
import io.shamash.asm.model.Severity
import io.shamash.asm.scan.ExternalBucketResolver
import io.shamash.asm.ui.dashboard.export.FindingsExport
import io.shamash.asm.ui.dashboard.export.FindingsExport.exportAllFindings
import io.shamash.psi.architecture.ControllerRules
import io.shamash.psi.architecture.DeadCodeRules
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.architecture.LayerRules
import io.shamash.psi.architecture.NamingRules
import org.objectweb.asm.Opcodes
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Tab Findings
 *
 * Aggregates:
 * PSI findings (lightweight deterministic scan using the same rule helpers see psi layer)
 * ASM findings (pure function from AsmIndex)
 *
 */
class AsmFindingsTabPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private enum class Source(
        val label: String,
    ) {
        ASM("ASM"),
        PSI("PSI"),
        ;

        override fun toString(): String = label
    }

    private data class Row(
        val source: Source,
        val severity: Severity,
        val title: String,
        val fqcn: String,
        val module: String,
        val layer: String,
        val message: String,
        val internal: String?,
        val evidence: Map<String, Any>,
    )

    private val header = JBLabel("Run scan to compute findings.")

    private val includeAsm = JBCheckBox("ASM", true)
    private val includePsi = JBCheckBox("PSI", true)

    private val severityBox = JComboBox(arrayOf("All") + Severity.entries.map { it.label }.toTypedArray())
    private val moduleBox = JComboBox(arrayOf("All"))
    private val layerBox = JComboBox(arrayOf("All", "Controller", "Service", "Repository", "Dao", "Util", "Workflow", "Api", "Unknown"))
    private val exportJsonBtn = JButton("Export JSON")
    private val exportXmlBtn = JButton("Export XML")
    private val packageField =
        JBTextField().apply {
            emptyText.text = "Package prefix filter (e.g. com.foo.service)"
        }

    private val details =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

    private val tableModel =
        ListTableModel<Row>(
            arrayOf(
                col("Src") { it.source.label },
                col("Severity") { it.severity.label },
                col("Title") { it.title },
                col("Class") { it.fqcn },
                col("Module") { it.module },
                col("Layer") { it.layer },
                col("Message") { it.message },
            ),
            mutableListOf(),
        )

    private val table =
        TableView(tableModel).apply {
            setAutoCreateRowSorter(true)
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "No findings."
        }

    private var asmIndex: AsmIndex? = null
    private var asmFindings: List<Finding> = emptyList()
    private var psiFindings: List<Finding> = emptyList()

    init {
        val top =
            JPanel(BorderLayout()).apply {
                val filters =
                    JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
                        add(JBLabel("Sources"))
                        add(includeAsm)
                        add(includePsi)
                        add(JBLabel("Severity"))
                        add(severityBox)
                        add(JBLabel("Module"))
                        add(moduleBox)
                        add(JBLabel("Layer"))
                        add(layerBox)
                        add(JBLabel("Package"))
                        add(packageField)
                    }
                add(filters, BorderLayout.NORTH)
                add(header, BorderLayout.SOUTH)
                val south =
                    JPanel(BorderLayout()).apply {
                        add(
                            JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
                                add(exportJsonBtn)
                                add(exportXmlBtn)
                            },
                            BorderLayout.SOUTH,
                        )
                    }
                add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
                add(south, BorderLayout.SOUTH)
            }

        val bottom =
            JPanel(BorderLayout()).apply {
                add(JBLabel("Evidence / Explanation"), BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(details), BorderLayout.CENTER)
            }

        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        exportJsonBtn.addActionListener {
            exportAllFindings(
                project = project,
                findings = asmFindings + psiFindings,
                format = FindingsExport.Format.JSON,
            )
        }

        exportXmlBtn.addActionListener {
            exportAllFindings(
                project = project,
                findings = asmFindings + psiFindings,
                format = FindingsExport.Format.XML,
            )
        }

        fun refresh() = applyFiltersAndRender()

        includeAsm.addActionListener { refresh() }
        includePsi.addActionListener { refresh() }
        severityBox.addActionListener { refresh() }
        moduleBox.addActionListener { refresh() }
        layerBox.addActionListener { refresh() }

        packageField.document.addDocumentListener(
            object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) = refresh()
            },
        )

        table.selectionModel.addListSelectionListener {
            val row = table.selectedObject ?: return@addListSelectionListener
            details.text =
                buildString {
                    appendLine("${row.source.label} • ${row.severity.label} • ${row.title}")
                    appendLine(row.fqcn)
                    appendLine("module=${row.module}, layer=${row.layer}")
                    appendLine()
                    appendLine(row.message)
                    if (row.evidence.isNotEmpty()) {
                        appendLine()
                        appendLine("Evidence:")
                        row.evidence.keys.sorted().forEach { k ->
                            appendLine(" - $k = ${row.evidence[k]}")
                        }
                    }
                }
        }

        table.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount != 2) return
                    val row = table.selectedObject ?: return
                    row.internal?.let { navigateToSourceIfInProject(it) }
                }
            },
        )
    }

    fun onIndexUpdated(newIndex: AsmIndex) {
        asmIndex = newIndex
        asmFindings = AsmFindingsEngine.fromIndex(newIndex)

        // refresh list from ASM immediately; PSI will add more later
        refreshModuleBox(buildModuleList(asmFindings, psiFindings))

        header.text = "Findings: ASM=${asmFindings.size}. PSI: computing…"
        applyFiltersAndRender()

        // PSI scan in background
        kickOffPsiScan()
    }

    private fun kickOffPsiScan() {
        ReadAction
            .nonBlocking<List<Finding>> {
                PsiFindingsEngine.scanProject(project)
            }.inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { findings ->
                psiFindings = findings
                refreshModuleBox(buildModuleList(asmFindings, psiFindings))
                header.text = "Findings: ASM=${asmFindings.size}, PSI=${psiFindings.size}."
                applyFiltersAndRender()
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun refreshModuleBox(modules: List<String>) {
        val selected = moduleBox.selectedItem?.toString() ?: "All"
        moduleBox.removeAllItems()
        moduleBox.addItem("All")
        modules.forEach { moduleBox.addItem(it) }
        moduleBox.selectedItem = if (modules.contains(selected)) selected else "All"
    }

    private fun applyFiltersAndRender() {
        val all = asmFindings + psiFindings
        val rows = all.map { f -> toRow(f) }

        val includeSources =
            mutableSetOf<Source>().apply {
                if (includeAsm.isSelected) add(Source.ASM)
                if (includePsi.isSelected) add(Source.PSI)
            }

        val severityPick = severityBox.selectedItem?.toString() ?: "All"
        val modulePick = moduleBox.selectedItem?.toString() ?: "All"
        val layerPick = layerBox.selectedItem?.toString() ?: "All"
        val pkgPrefix = packageField.text.trim()

        val filtered =
            rows
                .asSequence()
                .filter { it.source in includeSources }
                .filter { severityPick == "All" || it.severity.label == severityPick }
                .filter { modulePick == "All" || it.module == modulePick }
                .filter { layerPick == "All" || it.layer == layerPick }
                .filter { pkgPrefix.isEmpty() || it.fqcn.startsWith(pkgPrefix) }
                .sortedWith(compareBy<Row>({ it.severity.rank }, { it.source.label }, { it.fqcn }, { it.title }))
                .toList()

        tableModel.items = filtered.toMutableList()
        tableModel.fireTableDataChanged()

        if (filtered.isEmpty()) {
            details.text = ""
        }
    }

    private fun toRow(f: Finding): Row {
        val source =
            when {
                f.id.startsWith("ASM:") -> Source.ASM
                f.id.startsWith("PSI:") -> Source.PSI
                else -> Source.ASM
            }

        val fqcn = f.fqcn ?: "(no class)"
        val module = f.module ?: "(unknown)"
        val layer = inferLayerFromFqcn(fqcn)

        val internal = if (f.fqcn != null) f.fqcn.replace('.', '/') else null
        return Row(
            source = source,
            severity = f.severity,
            title = f.title,
            fqcn = fqcn,
            module = module,
            layer = layer,
            message = f.message,
            internal = internal,
            evidence = f.evidence,
        )
    }

    private fun inferLayerFromFqcn(fqcn: String): String {
        val s = fqcn.lowercase()
        val simple = fqcn.substringAfterLast('.')
        return when {
            s.contains(".controller.") || simple.endsWith("Controller") -> "Controller"
            s.contains(".service.") || simple.endsWith("Service") -> "Service"
            s.contains(".repository.") || simple.endsWith("Repository") -> "Repository"
            s.contains(".dao.") || simple.endsWith("Dao") -> "Dao"
            s.contains(".workflow.") || simple.endsWith("Workflow") -> "Workflow"
            s.contains(".api.") || simple.endsWith("Api") -> "Api"
            s.contains(".util.") || simple.endsWith("Util") -> "Util"
            else -> "Unknown"
        }
    }

    private fun buildModuleList(
        a: List<Finding>,
        b: List<Finding>,
    ): List<String> =
        (a.asSequence().mapNotNull { it.module } + b.asSequence().mapNotNull { it.module })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

    private fun navigateToSourceIfInProject(internal: String) {
        val fqcn = internal.replace('/', '.')
        val psiClass =
            JavaPsiFacade
                .getInstance(project)
                .findClass(fqcn, GlobalSearchScope.projectScope(project))
                ?: return

        val vf = psiClass.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, vf, psiClass.textOffset).navigate(true)
    }

    private fun <T> col(
        name: String,
        get: (Row) -> T,
    ) = object : ColumnInfo<Row, T>(name) {
        override fun valueOf(item: Row): T = get(item)
    }

    // TODO: move engine out

    private object AsmFindingsEngine {
        fun fromIndex(index: AsmIndex): List<Finding> {
            val classes = index.classes
            if (classes.isEmpty()) return emptyList()

            val projectSet = classes.keys

            // reverse edges: fan-in
            val fanIn = HashMap<String, Int>(classes.size)
            for ((from, refs) in index.references) {
                if (from !in projectSet) continue
                for (to in refs) {
                    if (to !in projectSet) continue
                    fanIn[to] = (fanIn[to] ?: 0) + 1
                }
            }

            val depthMemo = HashMap<String, Int>(classes.size)

            fun depthOf(internal: String): Int {
                depthMemo[internal]?.let { return it }
                val sup =
                    classes[internal]?.superInternalName ?: run {
                        depthMemo[internal] = 1
                        return 1
                    }
                val d = if (sup in classes) 1 + depthOf(sup) else 2
                depthMemo[internal] = d
                return d
            }

            fun isPublic(access: Int) = (access and Opcodes.ACC_PUBLIC) != 0

            fun publicMethodCount(info: AsmClassInfo): Int =
                info.methods.count { it.name != "<init>" && it.name != "<clinit>" && isPublic(it.access) }

            fun fanOutCount(info: AsmClassInfo): Int =
                info.referencedInternalNames.count { ExternalBucketResolver.bucketForInternalName(it).id != "ext:JDK" }

            fun godScore(
                info: AsmClassInfo,
                fin: Int,
                fout: Int,
                depth: Int,
            ): Int {
                val m = info.methods.size
                val pm = publicMethodCount(info)
                val f = info.fieldCount
                val ins = info.instructionCount / 50
                return (m * 3) + (pm * 2) + (f * 2) + ins + (fout * 2) + (fin * 2) + (depth - 1)
            }

            fun severityForGod(score: Int): Severity =
                when {
                    score >= 300 -> Severity.CRITICAL
                    score >= 220 -> Severity.HIGH
                    score >= 160 -> Severity.MEDIUM
                    else -> Severity.LOW
                }

            fun severityForDepth(depth: Int): Severity =
                when {
                    depth >= 10 -> Severity.HIGH
                    depth >= 7 -> Severity.MEDIUM
                    else -> Severity.LOW
                }

            fun severityForFanOut(fanOut: Int): Severity =
                when {
                    fanOut >= 200 -> Severity.HIGH
                    fanOut >= 120 -> Severity.MEDIUM
                    else -> Severity.LOW
                }

            fun driftSignals(info: AsmClassInfo): String? {
                val fq = info.fqcn
                val simple = fq.substringAfterLast('.')
                val role =
                    when {
                        fq.contains(".controller.", ignoreCase = true) || simple.endsWith("Controller") -> "Controller"
                        fq.contains(".service.", ignoreCase = true) || simple.endsWith("Service") -> "Service"
                        else -> return null
                    }
                val refs = info.referencedInternalNames

                if (role == "Service") {
                    val controllerRefs = refs.count { it.contains("/controller/") || it.endsWith("Controller") }
                    val webRefs =
                        refs.count {
                            it.contains("/web/") || it.contains("springframework/web") ||
                                it.contains("jakarta/servlet") || it.contains("javax/servlet")
                        }
                    val pub = publicMethodCount(info)
                    if ((controllerRefs > 0 || webRefs > 0) && pub >= 8) {
                        return "Service drift: publicMethods=$pub, controllerRefs=$controllerRefs, webRefs=$webRefs"
                    }
                }

                if (role == "Controller") {
                    val daoRefs = refs.count { it.contains("/dao/") || it.endsWith("Dao") }
                    val repoRefs = refs.count { it.contains("/repository/") || it.endsWith("Repository") }
                    if (daoRefs > 0 || repoRefs > 0) {
                        return "Controller drift: daoRefs=$daoRefs, repoRefs=$repoRefs"
                    }
                }

                return null
            }

            val out = mutableListOf<Finding>()

            // god classes: top 20
            val godTop =
                classes.values
                    .map { info ->
                        val d = depthOf(info.internalName)
                        val fout = fanOutCount(info)
                        val fin = fanIn[info.internalName] ?: 0
                        val score = godScore(info, fin, fout, d)
                        Triple(info, score, Triple(d, fout, fin))
                    }.sortedByDescending { it.second }
                    .take(20)

            for ((info, score, pack) in godTop) {
                val (d, fout, fin) = pack
                out +=
                    Finding(
                        id = "ASM:GOD_CLASS",
                        title = "God class candidate",
                        severity = severityForGod(score),
                        fqcn = info.fqcn,
                        module = info.moduleName,
                        message = "High complexity/coupling composite score. Higher score = more hotspot.",
                        evidence =
                            mapOf(
                                "score" to score,
                                "methodCount" to info.methods.size,
                                "publicMethodCount" to publicMethodCount(info),
                                "fieldCount" to info.fieldCount,
                                "instructionCount" to info.instructionCount,
                                "fanOut" to fout,
                                "fanIn" to fin,
                                "inheritanceDepth" to d,
                            ),
                    )
            }

            // deep inheritance
            for (info in classes.values) {
                val d = depthOf(info.internalName)
                if (d >= 6) {
                    out +=
                        Finding(
                            id = "ASM:DEEP_INHERITANCE",
                            title = "Deep inheritance chain",
                            severity = severityForDepth(d),
                            fqcn = info.fqcn,
                            module = info.moduleName,
                            message = "Inheritance depth is high (depth=$d).",
                            evidence =
                                mapOf(
                                    "depth" to d,
                                    "super" to (info.superInternalName?.replace('/', '.') ?: "(none)"),
                                ),
                        )
                }
            }

            // high fan-out: top 20
            val fanTop =
                classes.values
                    .map { info -> info to fanOutCount(info) }
                    .sortedByDescending { it.second }
                    .take(20)

            for ((info, fanOut) in fanTop) {
                out +=
                    Finding(
                        id = "ASM:HIGH_FANOUT",
                        title = "High fan-out",
                        severity = severityForFanOut(fanOut),
                        fqcn = info.fqcn,
                        module = info.moduleName,
                        message = "Class references many non-JDK types (fanOut=$fanOut).",
                        evidence =
                            mapOf(
                                "fanOut" to fanOut,
                                "refCountAll" to info.referencedInternalNames.size,
                            ),
                    )
            }

            // style drift (service/controller)
            for (info in classes.values) {
                val drift = driftSignals(info) ?: continue
                out +=
                    Finding(
                        id = "ASM:STYLE_DRIFT",
                        title = "Style drift",
                        severity = Severity.MEDIUM,
                        fqcn = info.fqcn,
                        module = info.moduleName,
                        message = drift,
                        evidence =
                            mapOf(
                                "publicMethodCount" to publicMethodCount(info),
                                "fanOutNonJdk" to fanOutCount(info),
                            ),
                    )
            }

            return out
        }
    }

    private object PsiFindingsEngine {
        fun scanProject(project: Project): List<Finding> {
            val out = mutableListOf<Finding>()
            val scope = GlobalSearchScope.projectScope(project)

            val query = AllClassesSearch.search(scope, project)
            for (psiClass in query) {
                val fqcn = psiClass.qualifiedName ?: continue

                val moduleName =
                    com.intellij.openapi.module.ModuleUtilCore
                        .findModuleForPsiElement(psiClass)
                        ?.name

                (NamingRules.bannedSuffix(psiClass)).let { suffix ->
                    out +=
                        Finding(
                            id = "PSI:NAMING_BANNED_SUFFIX",
                            title = "Banned suffix",
                            severity = Severity.MEDIUM,
                            fqcn = fqcn,
                            module = moduleName,
                            message = "Class name ends with banned suffix: $suffix",
                            evidence = mapOf("name" to (psiClass.name ?: "")),
                        )
                }

                if (NamingRules.isAbbreviated(psiClass)) {
                    out +=
                        Finding(
                            id = "PSI:NAMING_ABBREVIATED",
                            title = "Abbreviated class name",
                            severity = Severity.LOW,
                            fqcn = fqcn,
                            module = moduleName,
                            message = "Class name looks abbreviated and may hurt readability.",
                            evidence = mapOf("name" to (psiClass.name ?: "")),
                        )
                }

                val expected = LayerDetector.detect(psiClass)
                val forbiddenSvcToCtrl = LayerRules.serviceMustNotDependOnController(psiClass)
                if (forbiddenSvcToCtrl) {
                    out +=
                        Finding(
                            id = "PSI:LAYER_SERVICE_DEPENDS_ON_CONTROLLER",
                            title = "Service depends on Controller",
                            severity = Severity.HIGH,
                            fqcn = fqcn,
                            module = moduleName,
                            message = "Service should not depend on Controller.",
                            evidence = mapOf("expectedLayer" to expected.name),
                        )
                }

                val forbiddenCtrlToDao = LayerRules.controllerMustNotDependOnDao(psiClass)
                if (forbiddenCtrlToDao) {
                    out +=
                        Finding(
                            id = "PSI:LAYER_CONTROLLER_DEPENDS_ON_DAO",
                            title = "Controller depends on DAO",
                            severity = Severity.HIGH,
                            fqcn = fqcn,
                            module = moduleName,
                            message = "Controller should not depend on DAO directly.",
                            evidence = mapOf("expectedLayer" to expected.name),
                        )
                }

                val publicCount = ControllerRules.excessPublicMethods(psiClass).size
                if (publicCount > 0) {
                    out +=
                        Finding(
                            id = "PSI:CONTROLLER_PUBLIC_METHODS",
                            title = "Controller has too many public methods",
                            severity = if (publicCount >= 10) Severity.HIGH else Severity.MEDIUM,
                            fqcn = fqcn,
                            module = moduleName,
                            message = "Controller exposes many public methods (count=$publicCount).",
                            evidence = mapOf("publicMethods" to publicCount),
                        )
                }

                if (DeadCodeRules.isUnusedClass(psiClass)) {
                    out +=
                        Finding(
                            id = "PSI:DEAD_UNUSED_CLASS",
                            title = "Unused class",
                            severity = Severity.MEDIUM,
                            fqcn = fqcn,
                            module = moduleName,
                            message = "Class appears unused (based on references search).",
                            evidence = emptyMap(),
                        )
                }

                // unused methods findings
                val methods =
                    psiClass.methods
                        .asSequence()
                        .filter { it.name != "<init>" }
                        .take(200)
                        .toList()

                for (m in methods) {
                    if (DeadCodeRules.isUnusedMethod(m)) {
                        out +=
                            Finding(
                                id = "PSI:DEAD_UNUSED_METHOD",
                                title = "Unused method",
                                severity = Severity.LOW,
                                fqcn = fqcn,
                                module = moduleName,
                                message = "Method appears unused: ${m.name}${m.parameterList.text}",
                                evidence = mapOf("method" to m.name),
                            )
                    }
                }
            }

            return out
        }
    }
}
