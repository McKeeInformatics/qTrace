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

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * What a qTrace module adds to the Version window: a mode in which the record on screen is
 * turned into a workflow — packets of instructions that can be reordered, removed, added and
 * rewritten (the workflow editor module). Registered through {@link VersionEditors}; Core draws
 * the controls and knows nothing about the file the module writes.
 *
 * The record itself is never written: {@link #open} works on a copy.
 */
public interface VersionEditor {

    /** The button that enters the mode, e.g. "Super User". */
    String label();

    /**
     * Starts a workflow from a record. {@code traceRoot} is the .qtrace as read from
     * {@code trace} and must not be modified.
     */
    Document open(JsonObject traceRoot, File trace);

    /**
     * The workflow being composed. Packets are addressed by their position in
     * {@code root().sessions[]}, instructions by their position in the packet's {@code steps[]}.
     * A position out of range changes nothing.
     */
    interface Document {

        /**
         * The workflow in the shape of a .qtrace — one {@code sessions[]} entry per packet — so
         * the Version window and the replay read it as they read a record. Live: every change
         * shows in it.
         */
        JsonObject root();

        /** Moves an instruction up ({@code delta < 0}) or down inside its packet. */
        void moveStep(int packet, int step, int delta);

        /**
         * Moves an instruction to another place, in its packet or in another one: it lands
         * before the instruction now at {@code toStep} of {@code toPacket}
         * ({@code steps.size()}: at the end).
         */
        void moveStepTo(int packet, int step, int toPacket, int toStep);

        void removeStep(int packet, int step);

        /** Inserts an instruction at {@code at} ({@code steps.size()} appends). */
        void addStep(int packet, int at, String title, String script);

        void editStep(int packet, int step, String title, String script);

        void renamePacket(int packet, String title);

        void setPacketNotes(int packet, String notes);

        void movePacket(int packet, int delta);

        void removePacket(int packet);

        /** Inserts an empty packet at {@code at} ({@code sessions.size()} appends). */
        void addPacket(int at, String title);

        /** Appends the next packet's instructions to this one and removes the next packet. */
        void mergeWithNext(int packet);

        /**
         * Merges several packets into the first of them: their instructions follow one another
         * in the order the packets play, whatever order they are given in, and the other
         * packets are removed. Fewer than two packets, or one out of range, changes nothing.
         */
        void mergePackets(List<Integer> packets);

        /** Splits a packet in two: the instructions from {@code atStep} on go to a new packet after it. */
        void splitPacket(int packet, int atStep);

        /** What the author should know before saving, e.g. an instruction the replay would run only once. */
        List<String> warnings();

        /** Writes the workflow, signed by its author when a signing key is available. */
        void save(File file) throws IOException;

        /**
         * Publishes the workflow on qtrace.ca, signed like a saved one, with the classifiers its
         * instructions apply. Blocking, with network: not for the FX thread.
         *
         * @return what to tell the author, e.g. its ID and version
         * @throws IOException with a message for the author when qtrace.ca refuses or cannot be reached
         */
        String publish() throws IOException;
    }
}
