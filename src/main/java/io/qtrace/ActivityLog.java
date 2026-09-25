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

import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Activity log's memory. Everything qTrace reports is kept here from QuPath startup,
 * whether or not the panel is open, so opening the panel later shows what happened before.
 * The open panel attaches as the single sink: it gets the history once, then every new line.
 *
 * Each line is stamped with the local time it was logged (HH:mm:ss) — not when the panel
 * displays it, so history loaded on a late panel open keeps its real times.
 *
 * Bounded (oldest lines dropped) and thread-safe; the sink is called on the logging thread
 * — the panel hops to the FX thread itself.
 */
public final class ActivityLog {

    public static final int MAX_LINES = 5_000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String INDENT = " ".repeat("HH:mm:ss".length() + 2);

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static Consumer<String> sink;
    private static Clock clock = Clock.systemDefaultZone();

    private ActivityLog() {}

    public static void add(String message) {
        if (message == null) return;
        Consumer<String> s;
        synchronized (ActivityLog.class) {
            // Continuation lines line up under the text rather than getting their own time.
            String line = LocalTime.now(clock).format(TIME) + "  " + message.replace("\n", "\n" + INDENT);
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
            s = sink;
            if (s != null) {
                try { s.accept(line); } catch (Exception ignored) {}
            }
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
        clock = Clock.systemDefaultZone();
    }

    static synchronized void setClockForTests(Clock c) {
        clock = c;
    }
}
