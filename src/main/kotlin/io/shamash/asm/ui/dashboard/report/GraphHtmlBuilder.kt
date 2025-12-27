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
package io.shamash.asm.ui.dashboard.report

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets

/**
 * Builds a single-file standalone HTML for the graph view:
 * - inlines cytoscape.min.js from resources
 * - inlines graph JSON as a JS const
 * - renders on load
 *
 * No external assets / no CDN.
 */
object GraphHtmlBuilder {
    fun buildStandaloneHtml(
        project: Project,
        graphJson: String,
    ): String {
        val cytoscape =
            loadResourceText("/web/cytoscape.min.js")
                ?: error("Missing resource: /web/cytoscape.min.js")

        // embed as a JS string and JSON.parse it to avoid quoting edge cases.
        val graphJsonEscaped = jsStringLiteral(graphJson)

        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"utf-8\" />")
            appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />")
            appendLine("  <title>Shamash Graph</title>")
            appendLine("  <style>")
            appendLine(
                "    html, body { height: 100%; margin: 0; background: #0f1115; color: #d6d6d6; font-family: -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif; }",
            )
            appendLine(
                "    #topbar { display:flex; align-items:center; gap:12px; padding:10px 12px; border-bottom: 1px solid rgba(255,255,255,0.08); }",
            )
            appendLine("    #brand { font-weight: 700; letter-spacing: 0.4px; }")
            appendLine("    #score { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }")
            appendLine("    .pill { padding:4px 10px; border-radius:999px; font-size:12px; background: rgba(255,255,255,0.06); }")
            appendLine("    .pill strong { margin-right: 6px; }")
            appendLine("    #controls { margin-left:auto; display:flex; gap:10px; align-items:center; flex-wrap:wrap; }")
            appendLine("    label { font-size: 12px; opacity: 0.9; user-select:none; }")
            appendLine("    input[type=checkbox] { vertical-align: middle; }")
            appendLine(
                "    #layoutSel { background: rgba(255,255,255,0.07); color:#d6d6d6; border:1px solid rgba(255,255,255,0.14); padding:4px 8px; border-radius:10px; }",
            )
            appendLine("    #main { height: calc(100% - 48px); display:flex; }")
            appendLine("    #cy { flex: 1; }")
            appendLine("    #side { width: 360px; border-left: 1px solid rgba(255,255,255,0.08); padding: 10px; overflow:auto; }")
            appendLine("    #side h3 { margin: 6px 0 10px; }")
            appendLine("    #kv { font-size: 12px; line-height: 1.5; }")
            appendLine("    #kv .k { opacity: 0.75; }")
            appendLine("    #kv .v { margin-left: 6px; }")
            appendLine("    a { color: #8ab4ff; }")
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <div id=\"topbar\">")
            appendLine("    <div id=\"brand\">SHAMASH</div>")
            appendLine("    <div id=\"score\"></div>")
            appendLine("    <div id=\"controls\">")
            appendLine("      <label><input id=\"onlyViol\" type=\"checkbox\" /> Only violations</label>")
            appendLine("      <label><input id=\"hideJdk\" type=\"checkbox\" checked /> Hide JDK</label>")
            appendLine("      <label><input id=\"projOnly\" type=\"checkbox\" checked /> Project-only</label>")
            appendLine("      <select id=\"layoutSel\">")
            appendLine("        <option value=\"cose\" selected>cose</option>")
            appendLine("        <option value=\"breadthfirst\">breadthfirst</option>")
            appendLine("      </select>")
            appendLine("    </div>")
            appendLine("  </div>")
            appendLine("  <div id=\"main\">")
            appendLine("    <div id=\"cy\"></div>")
            appendLine("    <div id=\"side\">")
            appendLine("      <h3>Details</h3>")
            appendLine("      <div id=\"kv\">Click a node…</div>")
            appendLine("      <hr style=\"border:0;border-top:1px solid rgba(255,255,255,0.08); margin: 12px 0;\"/>")
            appendLine("      <div style=\"font-size:12px;opacity:0.85;\">")
            appendLine("        Standalone export generated by Shamash.")
            appendLine("      </div>")
            appendLine("    </div>")
            appendLine("  </div>")

            // Inline Cytoscape
            appendLine("  <script>")
            appendLine(cytoscape)
            appendLine("  </script>")

            // App
            appendLine("  <script>")
            appendLine("    const GRAPH_JSON = JSON.parse($graphJsonEscaped);")
            appendLine("    let cy = null;")
            appendLine("    function sevColor(sev) {")
            appendLine("      if (sev === 'ERROR') return '#ff4d4d';")
            appendLine("      if (sev === 'WARN') return '#ffb020';")
            appendLine("      if (sev === 'INFO') return '#65a9ff';")
            appendLine("      return '#9aa4b2';")
            appendLine("    }")
            appendLine("    function nodeSize(n) {")
            appendLine("      const g = n.godScore || 0;")
            appendLine("      const f = n.fanOut || 0;")
            appendLine("      const base = 18;")
            appendLine("      const bump = Math.min(28, Math.floor((g / 8) + (f / 15)));")
            appendLine("      return base + bump;")
            appendLine("    }")
            appendLine("    function weightOpacity(w) {")
            appendLine("      const ww = Math.max(1, Math.min(20, w || 1));")
            appendLine("      return Math.max(0.08, Math.min(0.6, ww / 25.0));")
            appendLine("    }")
            appendLine("    function renderScore(score) {")
            appendLine("      const el = document.getElementById('score');")
            appendLine("      if (!score) { el.innerHTML = ''; return; }")
            appendLine("      const pill = (k, v) => `<span class=\\\"pill\\\"><strong>${'$'}{k}</strong>${'$'}{v}</span>`;")
            appendLine("      el.innerHTML = [")
            appendLine("        pill('overall', score.overall),")
            appendLine("        pill('structural', score.structural),")
            appendLine("        pill('layering', score.layering),")
            appendLine("        pill('coupling', score.coupling),")
            appendLine("        pill('complexity', score.complexity)")
            appendLine("      ].join('');")
            appendLine("    }")
            appendLine("    function renderDetails(n) {")
            appendLine("      const kv = document.getElementById('kv');")
            appendLine("      if (!n) { kv.textContent = 'Click a node…'; return; }")
            appendLine("      const rows = [];")
            appendLine(
                "      const add = (k, v) => rows.push(`<div><span class=\\\"k\\\">${'$'}{k}:</span><span class=\\\"v\\\">${'$'}{v}</span></div>`);",
            )
            appendLine("      add('fqcn', n.fqcn);")
            appendLine("      add('module', n.module || '');")
            appendLine("      add('origin', n.origin || '');")
            appendLine("      add('layer', n.layer || '');")
            appendLine("      add('sevMax', n.sevMax || 'NONE');")
            appendLine("      add('findings', n.findingCount || 0);")
            appendLine("      add('fanIn', n.fanIn || 0);")
            appendLine("      add('fanOut', n.fanOut || 0);")
            appendLine("      add('depth', n.depth || 0);")
            appendLine("      add('godScore', n.godScore || 0);")
            appendLine("      kv.innerHTML = rows.join('');")
            appendLine("    }")
            appendLine("    function buildElements(graph, opts) {")
            appendLine("      const onlyViol = opts.onlyViol;")
            appendLine("      const hideJdk = opts.hideJdk;")
            appendLine("      const projOnly = opts.projOnly;")
            appendLine("      const nodes = [];")
            appendLine("      const keep = new Set();")
            appendLine("      for (const n of (graph.nodes || [])) {")
            appendLine("        if (onlyViol && (n.findingCount || 0) <= 0) continue;")
            appendLine("        if (hideJdk && n.origin === 'JDK') continue;")
            appendLine("        if (projOnly && n.origin !== 'PROJECT') continue;")
            appendLine("        keep.add(n.id);")
            appendLine("        nodes.push({ data: { ...n, label: n.fqcn } });")
            appendLine("      }")
            appendLine("      const edges = [];")
            appendLine("      for (const e of (graph.edges || [])) {")
            appendLine("        if (!keep.has(e.source) || !keep.has(e.target)) continue;")
            appendLine("        edges.push({ data: { ...e } });")
            appendLine("      }")
            appendLine("      return nodes.concat(edges);")
            appendLine("    }")
            appendLine("    function applyLayout(name) {")
            appendLine("      if (!cy) return;")
            appendLine("      const layout = cy.layout({ name: name || 'cose', animate: false, fit: true, padding: 30 });")
            appendLine("      layout.run();")
            appendLine("    }")
            appendLine("    function setGraph(graph) {")
            appendLine("      renderScore(graph.score);")
            appendLine("      const opts = {")
            appendLine("        onlyViol: document.getElementById('onlyViol').checked,")
            appendLine("        hideJdk: document.getElementById('hideJdk').checked,")
            appendLine("        projOnly: document.getElementById('projOnly').checked")
            appendLine("      };")
            appendLine("      const els = buildElements(graph, opts);")
            appendLine("      cy = cytoscape({")
            appendLine("        container: document.getElementById('cy'),")
            appendLine("        elements: els,")
            appendLine("        style: [")
            appendLine("          { selector: 'node', style: {")
            appendLine("              'background-color': (ele) => sevColor(ele.data('sevMax')),")
            appendLine("              'label': 'data(label)',")
            appendLine("              'color': '#d6d6d6',")
            appendLine("              'font-size': 10,")
            appendLine("              'text-wrap': 'wrap',")
            appendLine("              'text-max-width': 160,")
            appendLine("              'width': (ele) => nodeSize(ele.data()),")
            appendLine("              'height': (ele) => nodeSize(ele.data()),")
            appendLine("              'border-color': 'rgba(255,255,255,0.18)',")
            appendLine("              'border-width': 1")
            appendLine("            }")
            appendLine("          },")
            appendLine("          { selector: 'edge', style: {")
            appendLine("              'curve-style': 'bezier',")
            appendLine("              'target-arrow-shape': 'triangle',")
            appendLine("              'target-arrow-color': 'rgba(180,190,205,0.6)',")
            appendLine("              'line-color': 'rgba(180,190,205,0.35)',")
            appendLine("              'width': 1.2,")
            appendLine("              'opacity': (ele) => weightOpacity(ele.data('weight'))")
            appendLine("            }")
            appendLine("          },")
            appendLine("          { selector: 'node:selected', style: { 'border-color': '#8ab4ff', 'border-width': 2 } }")
            appendLine("        ]")
            appendLine("      });")
            appendLine("      cy.on('tap', 'node', (evt) => {")
            appendLine("        const data = evt.target.data();")
            appendLine("        renderDetails(data);")
            appendLine("        if (window._shamashNodeClick) {")
            appendLine("          try { window._shamashNodeClick(data.id); } catch (e) {}")
            appendLine("        }")
            appendLine("      });")
            appendLine("      applyLayout(document.getElementById('layoutSel').value);")
            appendLine("    }")
            appendLine("    function hookControls() {")
            appendLine("      const rerender = () => setGraph(GRAPH_JSON);")
            appendLine("      document.getElementById('onlyViol').addEventListener('change', rerender);")
            appendLine("      document.getElementById('hideJdk').addEventListener('change', rerender);")
            appendLine("      document.getElementById('projOnly').addEventListener('change', rerender);")
            appendLine("      document.getElementById('layoutSel').addEventListener('change', (e) => applyLayout(e.target.value));")
            appendLine("    }")
            appendLine("    hookControls();")
            appendLine("    setGraph(GRAPH_JSON);")
            appendLine("    window.setGraph = (jsonObj) => {")
            appendLine("      // for JCEF live updates: setGraph(<object>)")
            appendLine("      GRAPH_JSON.nodes = jsonObj.nodes;")
            appendLine("      GRAPH_JSON.edges = jsonObj.edges;")
            appendLine("      GRAPH_JSON.meta = jsonObj.meta;")
            appendLine("      GRAPH_JSON.score = jsonObj.score;")
            appendLine("      setGraph(GRAPH_JSON);")
            appendLine("    };")
            appendLine("  </script>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    private fun loadResourceText(path: String): String? {
        val stream = GraphHtmlBuilder::class.java.getResourceAsStream(path) ?: return null
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private fun jsStringLiteral(raw: String): String {
        // Return a JS string literal, including quotes.
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (ch in raw) {
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
}
