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
 * Builds a single-file standalone XHTML (XML-safe) HTML for the graph view:
 * - inlines cytoscape.min.js from resources
 * - inlines graph JSON as a JS string then JSON.parse()
 * - renders on load
 *
 * Why XHTML?
 * Some renderers/parsers treat exported HTML as XML. XML fails at "<!DOCTYPE ...>" with:
 *   StartTag: invalid element name (line 1 col 2)
 * XHTML avoids that permanently by being well-formed.
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
            // NOTE: no <!DOCTYPE> on purpose (XML parsers choke on it).
            appendLine("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"utf-8\" />")
            appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />")
            appendLine("  <title>Shamash Graph</title>")
            appendLine("  <style>")
            appendLine(
                """
                html, body {
                  height: 100%;
                  margin: 0;
                  background: #0f1115;
                  color: #d6d6d6;
                  font-family: -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif;
                }

                #topbar {
                  display: flex;
                  align-items: center;
                  gap: 12px;
                  padding: 10px 12px;
                  border-bottom: 1px solid rgba(255,255,255,0.08);
                }

                #brand {
                  font-weight: 700;
                  letter-spacing: 0.4px;
                  flex: 0 0 auto;
                }

                /* Keep pills in a stable left-to-right order without wrapping layout weirdness */
                #score {
                  display: flex;
                  gap: 8px;
                  align-items: center;
                  flex-wrap: nowrap;
                  overflow-x: auto;
                  overflow-y: hidden;
                  min-width: 0;
                  padding-bottom: 2px;
                }

                .pill {
                  display: inline-flex;
                  align-items: baseline;
                  gap: 6px;
                  padding: 4px 10px;
                  border-radius: 999px;
                  font-size: 12px;
                  background: rgba(255,255,255,0.06);
                  flex: 0 0 auto;
                  white-space: nowrap;
                }
                .pill strong { opacity: 0.9; }
                .pill .val { opacity: 0.95; }

                #controls {
                  margin-left: auto;
                  display: flex;
                  gap: 10px;
                  align-items: center;
                  flex-wrap: wrap;
                  flex: 0 0 auto;
                }

                label { font-size: 12px; opacity: 0.9; user-select: none; }
                input[type=checkbox] { vertical-align: middle; }

                #layoutSel {
                  background: rgba(255,255,255,0.07);
                  color: #d6d6d6;
                  border: 1px solid rgba(255,255,255,0.14);
                  padding: 4px 8px;
                  border-radius: 10px;
                }

                #main {
                  height: calc(100% - 48px);
                  display: flex;
                }

                #cy {
                  flex: 1;
                  min-width: 0;
                }

                #side {
                  width: 360px;
                  border-left: 1px solid rgba(255,255,255,0.08);
                  padding: 10px;
                  overflow: auto;
                }

                #side h3 { margin: 6px 0 10px; }
                #kv { font-size: 12px; line-height: 1.5; }
                #kv .k { opacity: 0.75; }
                #kv .v { margin-left: 6px; }

                a { color: #8ab4ff; }

                #err {
                  display: none;
                  position: fixed;
                  left: 12px;
                  bottom: 12px;
                  max-width: 70vw;
                  background: rgba(255,60,60,0.12);
                  border: 1px solid rgba(255,60,60,0.35);
                  padding: 10px 12px;
                  border-radius: 10px;
                  font-size: 12px;
                  white-space: pre-wrap;
                  z-index: 9999;
                }
                """.trimIndent(),
            )
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <div id=\"topbar\">")
            appendLine("    <div id=\"brand\">SHAMASH</div>")
            appendLine("    <div id=\"score\"></div>")
            appendLine("    <div id=\"controls\">")
            appendLine("      <label><input id=\"onlyViol\" type=\"checkbox\" /> Only violations</label>")
            appendLine("      <label><input id=\"hideJdk\" type=\"checkbox\" checked=\"checked\" /> Hide JDK</label>")
            appendLine("      <label><input id=\"projOnly\" type=\"checkbox\" checked=\"checked\" /> Project-only</label>")
            appendLine("      <select id=\"layoutSel\">")
            appendLine("        <option value=\"cose\" selected=\"selected\">cose</option>")
            appendLine("        <option value=\"breadthfirst\">breadthfirst</option>")
            appendLine("      </select>")
            appendLine("    </div>")
            appendLine("  </div>")
            appendLine("  <div id=\"main\">")
            appendLine("    <div id=\"cy\"></div>")
            appendLine("    <div id=\"side\">")
            appendLine("      <h3>Details</h3>")
            appendLine("      <div id=\"kv\">Click a node…</div>")
            appendLine("      <hr style=\"border:0;border-top:1px solid rgba(255,255,255,0.08); margin: 12px 0;\" />")
            appendLine("      <div style=\"font-size:12px;opacity:0.85;\">Standalone export generated by Shamash.</div>")
            appendLine("    </div>")
            appendLine("  </div>")
            appendLine("  <div id=\"err\"></div>")

            // Inline Cytoscape
            appendLine("  <script>")
            appendLine(cytoscape)
            appendLine("  </script>")

            // App
            appendLine("  <script>")
            appendLine(
                """
                (function () {
                  "use strict";

                  function showErr(msg) {
                    var el = document.getElementById("err");
                    if (!el) return;
                    el.style.display = "block";
                    el.textContent = msg;
                  }

                  function safeParseGraph() {
                    try {
                      return JSON.parse($graphJsonEscaped);
                    } catch (e) {
                      showErr("Failed to parse GRAPH_JSON: " + (e && e.message ? e.message : String(e)));
                      return { nodes: [], edges: [], meta: {}, score: {} };
                    }
                  }

                  var GRAPH_JSON = safeParseGraph();
                  var cy = null;

                  function sevColor(sev) {
                    if (sev === "ERROR") return "#ff4d4d";
                    if (sev === "WARN") return "#ffb020";
                    if (sev === "INFO") return "#65a9ff";
                    return "#9aa4b2";
                  }

                  function wrapFqcn(s) {
                    var str = (s == null) ? "" : String(s);
                    return str.split(".").join(".\u200B").split("$").join("$\u200B");
                  }

                  function clampInt(n, lo, hi) {
                    var x = parseInt(n, 10);
                    if (isNaN(x)) x = 0;
                    if (x < lo) x = lo;
                    if (x > hi) x = hi;
                    return x;
                  }

                  function fmtInt(n) {
                    var x = parseInt(n, 10);
                    if (isNaN(x)) x = 0;
                    return String(x);
                  }

                  function fmtPct(n) {
                    var x = parseFloat(n);
                    if (isNaN(x)) x = 0;
                    // keep it clean: whole percent
                    return String(Math.round(x)) + "%";
                  }

                  // overall     = overallScore%
                  // structural  = 100 - structuralScore
                  // complexity  = 100 - complexityScore
                  // coupling    = 100 - couplingScore
                  // layering    = 100 - layeringScore
                  function scoreValue(scoreObj, field, invert) {
                    if (!scoreObj) return 0;
                    var v = scoreObj[field];
                    var x = parseFloat(v);
                    if (isNaN(x)) x = 0;
                    if (invert) x = 100 - x;
                    // no percent for inverted ones
                    return clampInt(x, 0, 100);
                  }

                  function clearEl(el) {
                    while (el && el.firstChild) el.removeChild(el.firstChild);
                  }

                  function pillEl(k, vText) {
                    var sp = document.createElement("span");
                    sp.className = "pill";

                    var st = document.createElement("strong");
                    st.textContent = k;

                    var val = document.createElement("span");
                    val.className = "val";
                    val.textContent = vText;

                    sp.appendChild(st);
                    sp.appendChild(val);
                    return sp;
                  }

                  function renderScore(score) {
                    var el = document.getElementById("score");
                    if (!el) return;
                    clearEl(el);

                    if (!score) return;

                    // support both naming styles if you ever change backend:
                    var overallRaw = (score.overallScore != null) ? score.overallScore : score.overall;
                    var structuralRaw = (score.structuralScore != null) ? score.structuralScore : score.structural;
                    var complexityRaw = (score.complexityScore != null) ? score.complexityScore : score.complexity;
                    var couplingRaw = (score.couplingScore != null) ? score.couplingScore : score.coupling;
                    var layeringRaw = (score.layeringScore != null) ? score.layeringScore : score.layering;

                    // overall is shown as percent
                    el.appendChild(pillEl("overall clean architecture:", fmtPct(overallRaw)));

                    el.appendChild(pillEl("structural", fmtInt(100 - clampInt(structuralRaw, 0, 100))));
                    el.appendChild(pillEl("complexity", fmtInt(100 - clampInt(complexityRaw, 0, 100))));
                    el.appendChild(pillEl("coupling", fmtInt(100 - clampInt(couplingRaw, 0, 100))));
                    el.appendChild(pillEl("layering", fmtInt(100 - clampInt(layeringRaw, 0, 100))));
                  }

                  function addDetailRow(parent, k, v) {
                    var row = document.createElement("div");

                    var ks = document.createElement("span");
                    ks.className = "k";
                    ks.textContent = k + ":";

                    var vs = document.createElement("span");
                    vs.className = "v";
                    vs.textContent = (v == null) ? "" : String(v);

                    row.appendChild(ks);
                    row.appendChild(vs);
                    parent.appendChild(row);
                  }

                  function renderDetails(n) {
                    var kv = document.getElementById("kv");
                    if (!kv) return;

                    clearEl(kv);
                    if (!n) {
                      kv.textContent = "Click a node…";
                      return;
                    }

                    addDetailRow(kv, "fqcn", n.fqcn || "");
                    addDetailRow(kv, "module", n.module || "");
                    addDetailRow(kv, "origin", n.origin || "");
                    addDetailRow(kv, "layer", n.layer || "");
                    addDetailRow(kv, "sevMax", n.sevMax || "NONE");
                    addDetailRow(kv, "findings", n.findingCount || 0);
                    addDetailRow(kv, "fanIn", n.fanIn || 0);
                    addDetailRow(kv, "fanOut", n.fanOut || 0);
                    addDetailRow(kv, "depth", n.depth || 0);
                    addDetailRow(kv, "godScore", n.godScore || 0);
                  }

                  function nodeMinSize(n) {
                    // Keep stable minimum size but allow label-sized boxes.
                    var g = n.godScore || 0;
                    var f = n.fanOut || 0;
                    var base = 26;
                    var bump = Math.min(40, Math.floor((g / 6) + (f / 10)));
                    return base + bump;
                  }

                  function weightOpacity(w) {
                    var ww = Math.max(1, Math.min(20, w || 1));
                    return Math.max(0.10, Math.min(0.75, ww / 18.0));
                  }

                  function buildElements(graph, opts) {
                    var onlyViol = !!opts.onlyViol;
                    var hideJdk = !!opts.hideJdk;
                    var projOnly = !!opts.projOnly;

                    var nodes = [];
                    var edges = [];
                    var keep = {};

                    var ns = graph && graph.nodes ? graph.nodes : [];
                    for (var i = 0; i < ns.length; i++) {
                      var n = ns[i] || {};
                      if (onlyViol && (n.findingCount || 0) <= 0) continue;
                      if (hideJdk && n.origin === "JDK") continue;
                      if (projOnly && n.origin !== "PROJECT") continue;

                      if (n.id == null) continue;

                      keep[n.id] = true;

                      nodes.push({
                        data: {
                          // keep all original fields
                          id: n.id,
                          fqcn: n.fqcn || "",
                          module: n.module,
                          origin: n.origin,
                          layer: n.layer,
                          sevMax: n.sevMax,
                          findingCount: n.findingCount,
                          fanIn: n.fanIn,
                          fanOut: n.fanOut,
                          depth: n.depth,
                          godScore: n.godScore,
                          label: wrapFqcn(n.fqcn || n.id)
                        }
                      });
                    }

                    var es = graph && graph.edges ? graph.edges : [];
                    for (var j = 0; j < es.length; j++) {
                      var e = es[j] || {};
                      if (!keep[e.source] || !keep[e.target]) continue;
                      edges.push({ data: e });
                    }

                    return nodes.concat(edges);
                  }

                  function fitReadable() {
                    if (!cy) return;
                    cy.fit(cy.elements(), 120);

                    // If it ends up too zoomed-out, zoom in a bit to readable level.
                    // (breadthfirst tends to produce a wide layout)
                    var z = cy.zoom();
                    if (z < 0.35) {
                      cy.zoom(0.35);
                      cy.center();
                    }
                  }

                  function applyLayout(name) {
                    if (!cy) return;

                    var layoutName = name || "cose";
                    var layout;

                    if (layoutName === "breadthfirst") {
                      layout = cy.layout({
                        name: "breadthfirst",
                        directed: true,
                        padding: 140,
                        spacingFactor: 1.8,
                        avoidOverlap: true,
                        nodeDimensionsIncludeLabels: true,
                        animate: false,
                        fit: true
                      });
                    } else {
                      // Strong anti-overlap COSE tuning
                      layout = cy.layout({
                        name: "cose",
                        animate: false,
                        fit: true,
                        padding: 140,

                        // Spread things out
                        idealEdgeLength: 240,
                        nodeRepulsion: 2200000,
                        nodeOverlap: 50,
                        componentSpacing: 220,
                        edgeElasticity: 0.20,
                        gravity: 0.12,

                        // More iterations = more stable separation
                        numIter: 2600,
                        initialTemp: 900,
                        coolingFactor: 0.985,
                        minTemp: 1.0,

                        // overlap helpers
                        avoidOverlap: true,
                        nodeDimensionsIncludeLabels: true,
                        randomize: true,
                        refresh: 20
                      });
                    }

                    layout.run();
                    // After layout, enforce a readable fit (esp breadthfirst)
                    setTimeout(fitReadable, 0);
                  }

                  function setGraph(graph) {
                    renderScore(graph.score);

                    var opts = {
                      onlyViol: document.getElementById("onlyViol").checked,
                      hideJdk: document.getElementById("hideJdk").checked,
                      projOnly: document.getElementById("projOnly").checked
                    };

                    var els = buildElements(graph, opts);

                    if (cy) {
                      cy.destroy();
                      cy = null;
                    }

                    cy = cytoscape({
                      container: document.getElementById("cy"),
                      elements: els,
                      minZoom: 0.08,
                      maxZoom: 3.0,
                      wheelSensitivity: 0.20,
                      style: [
                        {
                          selector: "node",
                          style: {
                            "background-color": function (ele) { return sevColor(ele.data("sevMax")); },
                            "shape": "round-rectangle",

                            "label": "data(label)",
                            "color": "#d6d6d6",

                            // wrapping for FQCN
                            "text-wrap": "wrap",
                            "text-max-width": 360,
                            "font-size": 10,
                            "text-valign": "center",
                            "text-halign": "center",

                            // label-sized node with minimum size (prevents overlap/illegible tiny nodes)
                            "width": "label",
                            "height": "label",
                            "padding": 10,
                            "min-width": function (ele) { return nodeMinSize(ele.data()); },
                            "min-height": function (ele) { return nodeMinSize(ele.data()); },

                            "border-color": "rgba(255,255,255,0.18)",
                            "border-width": 1
                          }
                        },
                        {
                          selector: "edge",
                          style: {
                            "curve-style": "bezier",
                            "target-arrow-shape": "triangle",
                            "target-arrow-color": "rgba(180,190,205,0.65)",
                            "line-color": "rgba(180,190,205,0.40)",
                            "width": 1.3,
                            "opacity": function (ele) { return weightOpacity(ele.data("weight")); }
                          }
                        },
                        {
                          selector: "node:selected",
                          style: {
                            "border-color": "#8ab4ff",
                            "border-width": 2
                          }
                        }
                      ]
                    });

                    cy.on("tap", "node", function (evt) {
                      var data = evt.target.data();
                      renderDetails(data);
                      if (window._shamashNodeClick) {
                        try { window._shamashNodeClick(data.id); } catch (e) {}
                      }
                    });

                    applyLayout(document.getElementById("layoutSel").value);
                  }

                  function hookControls() {
                    var rerender = function () { setGraph(GRAPH_JSON); };
                    document.getElementById("onlyViol").addEventListener("change", rerender);
                    document.getElementById("hideJdk").addEventListener("change", rerender);
                    document.getElementById("projOnly").addEventListener("change", rerender);
                    document.getElementById("layoutSel").addEventListener("change", function (e) {
                      applyLayout(e.target.value);
                    });
                  }

                  hookControls();
                  setGraph(GRAPH_JSON);

                  // For JCEF live updates: setGraph(<object>)
                  window.setGraph = function (jsonObj) {
                    GRAPH_JSON.nodes = jsonObj.nodes;
                    GRAPH_JSON.edges = jsonObj.edges;
                    GRAPH_JSON.meta = jsonObj.meta;
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
