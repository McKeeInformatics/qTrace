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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One change to QuPath's list of classes (the Annotations tab's class panel: Add/Remove &gt; Add
 * class, Remove class, Populate from…, Reset to default classes…): a class added, with its
 * colour, or removed.
 *
 * QuPath never pushes anything to the history workflow for these, so a recorded session would
 * otherwise start with classes that appear from nowhere. They are replayable: the list is a
 * public, scriptable {@code getQuPath().getAvailablePathClasses()}, so
 * {@code QTraceReplayEngine} turns each record into a real step.
 *
 * Created by ActionLogger.classListChanged(); consumed by QTraceExporter.
 */
public class ClassListRecord {

    public static final String ADD = "add";
    public static final String REMOVE = "remove";

    /** A class as the list shows it: its full name ("Tumor", "Tumor: Positive") and its colour. */
    public record Entry(String name, Integer colorRgb) {}

    public final String  action;
    public final String  name;
    public final Integer colorRgb; // packed RGB, null when the class has none
    public final Instant timestamp;

    public ClassListRecord(String action, String name, Integer colorRgb, Instant timestamp) {
        this.action    = action;
        this.name      = name;
        this.colorRgb  = colorRgb;
        this.timestamp = timestamp;
    }

    /**
     * What changed between two states of the list: classes that are gone, then classes that are
     * new or whose colour changed (an add sets the colour again), each in list order. The order
     * of the list itself is not a change.
     */
    public static List<ClassListRecord> diff(List<Entry> before, List<Entry> after, Instant at) {
        Map<String, Integer> was = new LinkedHashMap<>(), is = new LinkedHashMap<>();
        before.forEach(e -> was.put(e.name(), e.colorRgb()));
        after.forEach(e -> is.put(e.name(), e.colorRgb()));
        List<ClassListRecord> out = new ArrayList<>();
        was.forEach((name, rgb) -> {
            if (!is.containsKey(name)) out.add(new ClassListRecord(REMOVE, name, rgb, at));
        });
        is.forEach((name, rgb) -> {
            if (!was.containsKey(name) || !Objects.equals(was.get(name), rgb)) out.add(new ClassListRecord(ADD, name, rgb, at));
        });
        return out;
    }
}
