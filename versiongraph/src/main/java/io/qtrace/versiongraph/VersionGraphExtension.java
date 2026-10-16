/*
 * qTrace — QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package io.qtrace.versiongraph;

import io.qtrace.VersionGraph;
import io.qtrace.VersionGraphs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Brings the Version window to Core ({@link VersionGraphs}). Core shows Version(s) in the panel
 * and the mini-panel only once this module has registered, and opens the window through it —
 * for the panel's button and the modules that open a record or a workflow alike.
 */
public class VersionGraphExtension implements QuPathExtension {

    private static final Logger log = LoggerFactory.getLogger(VersionGraphExtension.class);
    private static final String TAG = "[qtrace-versiongraph] ";

    private static boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) return;
        installed = true;
        try {
            VersionGraphs.register("versiongraph", new VersionGraph() {
                @Override public Window create(Host host) {
                    QTraceCommitGraph window = new QTraceCommitGraph(qupath);
                    window.setHost(host);
                    return window;
                }

                @Override public View view() {
                    QTraceCommitGraph graph = new QTraceCommitGraph(qupath);
                    javafx.scene.Node content = graph.embedded();
                    return new View() {
                        @Override public javafx.scene.Node node() { return content; }
                        @Override public void show(java.io.File record) { graph.load(record); }
                    };
                }
            });
            log.info(TAG + "loaded: the Version window is available");
        } catch (LinkageError e) {
            // A Core published before VersionGraphs existed: nothing to plug into, nothing breaks.
            log.warn(TAG + "this qTrace Core is too old for the Version window module — update qTrace Core");
        }
    }

    @Override
    public String getName() {
        return "qTrace Version Graph";
    }

    @Override
    public String getDescription() {
        return "The history of a record as a graph of its sessions: who did what, what was stamped, "
            + "and which instructions a replay runs.";
    }
}
