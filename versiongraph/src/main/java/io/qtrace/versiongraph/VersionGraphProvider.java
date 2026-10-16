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
import javafx.scene.Node;
import qupath.lib.gui.QuPathGUI;

import java.io.File;

/**
 * The Version window and its content for another window (the Dashboard's Version tab).
 * Its own class, loaded only by a Core that has {@link VersionGraph.View}: the JVM rejects a
 * class whose methods return a type it cannot find, and with it the whole Version window.
 */
final class VersionGraphProvider implements VersionGraph {

    private final QuPathGUI qupath;

    VersionGraphProvider(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    static QTraceCommitGraph window(QuPathGUI qupath, Host host) {
        QTraceCommitGraph window = new QTraceCommitGraph(qupath);
        window.setHost(host);
        return window;
    }

    @Override public Window create(Host host) {
        return window(qupath, host);
    }

    @Override public View view() {
        QTraceCommitGraph graph = new QTraceCommitGraph(qupath);
        Node content = graph.embedded();
        return new View() {
            @Override public Node node() { return content; }
            @Override public void show(File record) { graph.load(record); }
        };
    }
}
