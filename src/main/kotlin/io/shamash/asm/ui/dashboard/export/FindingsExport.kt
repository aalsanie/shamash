package io.shamash.asm.ui.dashboard.export

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import io.shamash.asm.model.Finding
import io.shamash.asm.model.Severity
import java.nio.charset.Charset
import java.nio.file.Paths

object FindingsExport {

    enum class Format(val ext: String) { JSON("json"), XML("xml") }

    private const val SCHEMA = "shamash.findings.v1"

    fun exportAllFindings(
        project: Project,
        findings: List<Finding>,
        format: Format,
        defaultBaseName: String = "shamash-findings",
        charset: Charset = Charsets.UTF_8
    ) {
        if (findings.isEmpty()) {
            notify(project, "Nothing to export", "No findings available yet. Run scan first.", NotificationType.WARNING)
            return
        }

        val content = when (format) {
            Format.JSON -> findingsToJson(findings)
            Format.XML -> findingsToXml(findings)
        }

        val descriptor = FileSaverDescriptor(
            "Export Shamash Findings",
            "Export findings (all severities).",
            format.ext
        )

        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(Paths.get(""), "findings.${format.ext}") ?: return

        try {
            val file = wrapper.file
            FileUtil.writeToFile(file, content, charset)
            notify(
                project,
                "Export complete",
                "Saved ${findings.size} findings to: ${file.absolutePath}",
                NotificationType.INFORMATION
            )
        } catch (t: Throwable) {
            notify(project, "Export failed", t.message ?: t::class.java.simpleName, NotificationType.ERROR)
        }
    }

    fun findingsToJson(findings: List<Finding>): String {
        val sorted = stableSort(findings)

        val sb = StringBuilder(sorted.size * 180)
        sb.append("{")
        sb.append("\"schema\":").append(jsonStr(SCHEMA)).append(',')
        sb.append("\"count\":").append(sorted.size).append(',')
        sb.append("\"findings\":[")
        for (i in sorted.indices) {
            if (i > 0) sb.append(',')
            val f = sorted[i]
            sb.append('{')
            sb.append("\"id\":").append(jsonStr(f.id)).append(',')
            sb.append("\"title\":").append(jsonStr(f.title)).append(',')
            sb.append("\"severity\":").append(jsonStr(f.severity.label)).append(',')
            sb.append("\"severityRank\":").append(f.severity.rank).append(',')
            sb.append("\"fqcn\":").append(if (f.fqcn == null) "null" else jsonStr(f.fqcn)).append(',')
            sb.append("\"module\":").append(if (f.module == null) "null" else jsonStr(f.module)).append(',')
            sb.append("\"message\":").append(jsonStr(f.message)).append(',')
            sb.append("\"evidence\":").append(jsonAny(f.evidence))
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun findingsToXml(findings: List<Finding>): String {
        val sorted = stableSort(findings)

        val sb = StringBuilder(sorted.size * 220)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<shamashFindings schema="$SCHEMA" count="${sorted.size}">""").append('\n')

        for (f in sorted) {
            sb.append("  <finding")
                .append(""" id="${xmlAttr(f.id)}"""")
                .append(""" title="${xmlAttr(f.title)}"""")
                .append(""" severity="${xmlAttr(f.severity.label)}"""")
                .append(""" severityRank="${f.severity.rank}"""")
                .append(""" fqcn="${xmlAttr(f.fqcn ?: "")}"""")
                .append(""" module="${xmlAttr(f.module ?: "")}"""")
                .append(">")
                .append('\n')

            sb.append("    <message>").append(xmlText(f.message)).append("</message>\n")

            sb.append("    <evidence>\n")
            for (k in f.evidence.keys.map { it.toString() }.sorted()) {
                val v = f.evidence[k]
                sb.append("""      <entry key="${xmlAttr(k)}">""")
                    .append(xmlText(evidenceValueToString(v)))
                    .append("</entry>\n")
            }
            sb.append("    </evidence>\n")
            sb.append("  </finding>\n")
        }

        sb.append("</shamashFindings>\n")
        return sb.toString()
    }

    // ----------------- helpers -----------------

    private fun stableSort(findings: List<Finding>): List<Finding> {
        return findings.sortedWith(
            compareBy<Finding>(
                { it.severity.rank },
                { it.id },
                { it.fqcn ?: "" },
                { it.title }
            )
        )
    }

    private fun evidenceValueToString(v: Any?): String {
        return when (v) {
            null -> "null"
            is String -> v
            is Number, is Boolean -> v.toString()
            is Map<*, *> -> v.entries.joinToString(prefix = "{", postfix = "}") { (k, vv) ->
                "${k.toString()}=${evidenceValueToString(vv)}"
            }
            is Iterable<*> -> v.joinToString(prefix = "[", postfix = "]") { evidenceValueToString(it) }
            is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { evidenceValueToString(it) }
            else -> v.toString()
        }
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun jsonAny(v: Any?): String {
        return when (v) {
            null -> "null"
            is String -> jsonStr(v)
            is Number, is Boolean -> v.toString()
            is Map<*, *> -> {
                val keys = v.keys.filterNotNull().map { it.toString() }.sorted()
                buildString {
                    append('{')
                    var first = true
                    for (k in keys) {
                        if (!first) append(',')
                        first = false
                        append(jsonStr(k)).append(':').append(jsonAny(v[k]))
                    }
                    append('}')
                }
            }
            is Iterable<*> -> v.joinToString(prefix = "[", postfix = "]") { jsonAny(it) }
            is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { jsonAny(it) }
            else -> jsonStr(v.toString())
        }
    }

    private fun xmlAttr(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun xmlText(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Shamash")
            .createNotification(title, content, type)
            .notify(project)
    }
}
