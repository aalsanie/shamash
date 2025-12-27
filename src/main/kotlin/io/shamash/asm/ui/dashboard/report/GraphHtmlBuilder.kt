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
            appendLine("    #kv .v { margin-left: 6px; word-break: break-word; }")
            appendLine("    a { color: #8ab4ff; }")
            appendLine(
                "    #err { display:none; position:fixed; left:12px; bottom:12px; max-width: 70vw; background: rgba(255,60,60,0.12); border:1px solid rgba(255,60,60,0.35); padding:10px 12px; border-radius:10px; font-size:12px; white-space:pre-wrap; }",
            )
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
            appendLine("      <div style=\"font-size:12px;opacity:0.85;\">Standalone export generated by Shamash.</div>")
            appendLine("    </div>")
            appendLine("  </div>")
            appendLine("  <div id=\"err\"></div>")

            // Inline Cytoscape
            appendLine("  <script>")
            appendLine(cytoscape)
            appendLine("  </script>")

            // App JS (clean + robust)
            appendLine("  <script>")
            appendLine(
                """
                (function () {
                  const errBox = document.getElementById('err');
                  const showErr = (msg, e) => {
                    console.error(msg, e || '');
                    errBox.style.display = 'block';
                    errBox.textContent = msg + (e ? ("\n" + (e.stack || e.message || String(e))) : "");
                  };

                  let GRAPH_JSON;
                  try {
                    GRAPH_JSON = JSON.parse($graphJsonEscaped);
                  } catch (e) {
                    showErr("Failed to parse GRAPH_JSON", e);
                    return;
                  }

                  let cy = null;

                  function sevColor(sev) {
                    if (sev === 'ERROR') return '#ff4d4d';
                    if (sev === 'WARN') return '#ffb020';
                    if (sev === 'INFO') return '#65a9ff';
                    return '#9aa4b2';
                  }

                  function nodeMinSize(n) {
                    const g = n.godScore || 0;
                    const f = n.fanOut || 0;
                    const base = 18;
                    const bump = Math.min(28, Math.floor((g / 8) + (f / 15)));
                    return base + bump;
                  }

                  function weightOpacity(w) {
                    const ww = Math.max(1, Math.min(20, w || 1));
                    return Math.max(0.08, Math.min(0.6, ww / 25.0));
                  }

                  // Insert ZWSP after '.' and '$' so wrap can break long FQCNs.
                  function wrapFqcn(s) {
                    return (s || '')
                      .replaceAll('.', '.\u200B')
                      .replaceAll('$', '$\u200B');
                  }

                  function renderScore(score) {
                    const el = document.getElementById('score');
                    if (!score) { el.innerHTML = ''; return; }
                    const pill = (k, v) => `<span class="pill"><strong>${'$'}{k}</strong>${'$'}{v}</span>`;
                    el.innerHTML = [
                      pill('overall clean solution%', score.overall),
                      pill('structural', score.structural),
                      pill('layering', score.layering),
                      pill('coupling', score.coupling),
                      pill('complexity', score.complexity)
                    ].join('');
                  }

                  function renderDetails(n) {
                    const kv = document.getElementById('kv');
                    if (!n) { kv.textContent = 'Click a node…'; return; }
                    const rows = [];
                    const add = (k, v) => rows.push(`<div><span class="k">${'$'}{k}:</span><span class="v">${'$'}{v}</span></div>`);
                    add('fqcn', n.fqcn);
                    add('module', n.module || '');
                    add('origin', n.origin || '');
                    add('layer', n.layer || '');
                    add('sevMax', n.sevMax || 'NONE');
                    add('findings', n.findingCount || 0);
                    add('fanIn', n.fanIn || 0);
                    add('fanOut', n.fanOut || 0);
                    add('depth', n.depth || 0);
                    add('godScore', n.godScore || 0);
                    kv.innerHTML = rows.join('');
                  }

                  function buildElements(graph, opts) {
                    const onlyViol = opts.onlyViol;
                    const hideJdk  = opts.hideJdk;
                    const projOnly = opts.projOnly;

                    const nodes = [];
                    const keep = new Set();

                    for (const n of (graph.nodes || [])) {
                      if (onlyViol && (n.findingCount || 0) <= 0) continue;
                      if (hideJdk && n.origin === 'JDK') continue;
                      if (projOnly && n.origin !== 'PROJECT') continue;

                      keep.add(n.id);
                      nodes.push({
                        data: {
                          ...n,
                          // keep full fqcn for details; label is wrapped fqcn for rendering
                          label: wrapFqcn(n.fqcn),
                          _minSize: nodeMinSize(n)
                        }
                      });
                    }

                    const edges = [];
                    for (const e of (graph.edges || [])) {
                      if (!keep.has(e.source) || !keep.has(e.target)) continue;
                      edges.push({ data: { ...e } });
                    }

                    return nodes.concat(edges);
                  }

                  function applyLayout(name) {
                    if (!cy) return;

                    const layout = cy.layout({
                      name: name || 'cose',
                      animate: false,
                      fit: true,
                      padding: 80,

                      // spread out more
                      nodeRepulsion: 1200000,
                      idealEdgeLength: 160,
                      nodeOverlap: 40,
                      gravity: 0.25,
                      numIter: 1500,

                      avoidOverlap: true,
                      nodeDimensionsIncludeLabels: true
                    });

                    layout.run();
                  }

                  function setGraph(graph) {
                    renderScore(graph.score);

                    const opts = {
                      onlyViol: document.getElementById('onlyViol').checked,
                      hideJdk:  document.getElementById('hideJdk').checked,
                      projOnly: document.getElementById('projOnly').checked
                    };

                    const els = buildElements(graph, opts);

                    cy = cytoscape({
                      container: document.getElementById('cy'),
                      elements: els,
                      style: [
                        {
                          selector: 'node',
                          style: {
                            'background-color': (ele) => sevColor(ele.data('sevMax')),
                            'label': 'data(label)',
                            'color': '#d6d6d6',

                            'font-size': 10,
                            'text-wrap': 'wrap',
                            'text-max-width': 280,

                            'shape': 'round-rectangle',

                            // KEY FIX: size nodes to label so long FQCN doesn't spill outside tiny nodes
                            'width': 'label',
                            'height': 'label',
                            'padding': 10,

                            // Keep your scoring-based minimum size
                            'min-width':  (ele) => ele.data('_minSize') || 18,
                            'min-height': (ele) => ele.data('_minSize') || 18,

                            'text-valign': 'center',
                            'text-halign': 'center',

                            'border-color': 'rgba(255,255,255,0.18)',
                            'border-width': 1
                          }
                        },
                        {
                          selector: 'edge',
                          style: {
                            'curve-style': 'bezier',
                            'target-arrow-shape': 'triangle',
                            'target-arrow-color': 'rgba(180,190,205,0.6)',
                            'line-color': 'rgba(180,190,205,0.35)',
                            'width': 1.2,
                            'opacity': (ele) => weightOpacity(ele.data('weight'))
                          }
                        },
                        { selector: 'node:selected', style: { 'border-color': '#8ab4ff', 'border-width': 2 } }
                      ]
                    });

                    cy.on('tap', 'node', (evt) => {
                      const data = evt.target.data();
                      renderDetails(data);
                      if (window._shamashNodeClick) {
                        try { window._shamashNodeClick(data.id); } catch (e) {}
                      }
                    });

                    applyLayout(document.getElementById('layoutSel').value);
                  }

                  function hookControls() {
                    const rerender = () => setGraph(GRAPH_JSON);
                    document.getElementById('onlyViol').addEventListener('change', rerender);
                    document.getElementById('hideJdk').addEventListener('change', rerender);
                    document.getElementById('projOnly').addEventListener('change', rerender);
                    document.getElementById('layoutSel').addEventListener('change', (e) => applyLayout(e.target.value));
                  }

                  try {
                    hookControls();
                    setGraph(GRAPH_JSON);
                  } catch (e) {
                    showErr("Graph runtime error", e);
                  }

                  window.setGraph = (jsonObj) => {
                    GRAPH_JSON.nodes = jsonObj.nodes;
                    GRAPH_JSON.edges = jsonObj.edges;
                    GRAPH_JSON.meta  = jsonObj.meta;
                    GRAPH_JSON.score = jsonObj.score;
                    setGraph(GRAPH_JSON);
                  };
                })();
                """.trimIndent(),
            )
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
