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

package io.qtrace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Activity log's memory. Everything qTrace reports is kept here from QuPath startup,
 * whether or not the panel is open, so opening the panel later shows what happened before.
 * The open panel attaches as the single sink: it gets the history once, then every new line.
 *
 * Bounded (oldest lines dropped) and thread-safe; the sink is called on the logging thread
 * — the panel hops to the FX thread itself.
 */
public final class ActivityLog {

    public static final int MAX_LINES = 5_000;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static Consumer<String> sink;

    private ActivityLog() {}

    public static void add(String line) {
        if (line == null) return;
        Consumer<String> s;
        synchronized (ActivityLog.class) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
            s = sink;
        }
        if (s != null) {
            try { s.accept(line); } catch (Exception ignored) {}
        }
    }

    public static synchronized List<String> lines() {
        return new ArrayList<>(LINES);
    }

    /** Makes {@code newSink} the only receiver of new lines and returns the history so far. */
    public static synchronized List<String> attach(Consumer<String> newSink) {
        sink = newSink;
        return new ArrayList<>(LINES);
    }

    /** Stops sending lines to {@code oldSink}, if it is still the current one. */
    public static synchronized void detach(Consumer<String> oldSink) {
        if (sink == oldSink) sink = null;
    }

    static synchronized void resetForTests() {
        LINES.clear();
        sink = null;
    }
}
