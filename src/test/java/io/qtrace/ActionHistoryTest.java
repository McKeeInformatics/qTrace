/*
 * qTrace - QuPath workflow provenance extension
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The action model behind Undo / Redo (docs/architecture/undo-redo.md, "Design"), driven the way
 * QuPath 0.7.0's UndoRedoManager moves its line: a snapshot is a byte[], its identity a state.
 * The scenarios are Lot A's (docs/architecture/undo-redo-evidence/).
 */
class ActionHistoryTest {

    /** QuPath's undo line for one viewer: undoStack (oldest first), current, redoStack - as UndoLine reads it. */
    static final class Stack {
        final List<byte[]> undo = new ArrayList<>();
        byte[] current;
        final List<byte[]> redo = new ArrayList<>(); // next redo first
        int cap = 1000;

        Stack(String initial) { current = state(initial); }

        /** A hierarchy event that takes a snapshot (SerializableUndoRedoStack.addLatest). */
        Stack snap(String content) {
            undo.add(current);
            current = state(content);
            redo.clear();
            while (undo.size() > cap) undo.remove(0);
            return this;
        }

        boolean undo() {
            if (undo.isEmpty()) return false;
            redo.add(0, current);
            current = undo.remove(undo.size() - 1);
            return true;
        }

        boolean redo() {
            if (redo.isEmpty()) return false;
            undo.add(current);
            current = redo.remove(0);
            return true;
        }

        List<Object> line() {
            List<Object> l = new ArrayList<>(undo);
            l.add(current);
            l.addAll(redo);
            return l;
        }

        int position() { return undo.size(); }

        static byte[] state(String content) { return content.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    }

    final ActionHistory history = new ActionHistory();
    final Stack qupath = new Stack("empty");

    List<CaptureEvents.ActionChange> follow() {
        return history.follow(qupath.line(), qupath.position(), true);
    }

    /** One gesture: its snapshots, seen after each hierarchy event, then its step captured and sealed. */
    long command(String... snapshots) {
        for (String s : snapshots) { qupath.snap(s); follow(); }
        long n = history.captured();
        history.seal();
        return n;
    }

    @Test
    void eachActionHasItsOwnStableNumber() {
        long a = command("A");
        long b = command("A+B");
        assertTrue(b > a);
        assertEquals(ActionHistory.ACTIVE, history.state(a));
        assertEquals(ActionHistory.ACTIVE, history.state(b));
    }

    @Test
    void twoStepsCapturedInTheSamePulseAreOneAction() {
        // setImageType writes "Set image type" and "Set color deconvolution stains", no snapshot
        long first = history.captured();
        long second = history.captured();
        history.seal();
        assertEquals(first, second);
        assertNotEquals(first, history.captured());
    }

    @Test
    void ctrlZUndoesTheLastActionNotTheOneBefore() {
        long hand = command("hand");
        long expand = command("hand+polygon");
        qupath.undo();
        List<CaptureEvents.ActionChange> changes = follow();
        assertEquals(List.of(new CaptureEvents.ActionChange(CaptureEvents.ActionChange.UNDONE, List.of(expand), changes.get(0).at())), changes);
        assertEquals(ActionHistory.UNDONE, history.state(expand));
        assertEquals(ActionHistory.ACTIVE, history.state(hand));
    }

    @Test
    void anActionWithoutSnapshotNeverTakesACtrlZ() {
        long hand = command("hand");
        long imageType = command(); // setImageType: 0 snapshot
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(hand));
        assertEquals(ActionHistory.ACTIVE, history.state(imageType));
    }

    @Test
    void cellDetectionIsUndoneByTheFirstCtrlZThatRemovesItsDetections() {
        long hand = command("hand");
        // preprocess clears old detections (same hierarchy as before), then the detections come
        long detection = command("hand", "hand+cells");
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(detection), "the detections are gone");
        assertEquals(ActionHistory.ACTIVE, history.state(hand));
        qupath.undo(); // back to "hand": the detection's first snapshot, nothing visible changes
        assertTrue(follow().isEmpty(), "the second Ctrl+Z of the same action marks nothing");
        assertEquals(ActionHistory.ACTIVE, history.state(hand));
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(hand), "the third Ctrl+Z undoes the action before");
    }

    @Test
    void expandAnnotationsTrailingIdenticalSnapshotIsNotAStepBack() {
        long hand = command("hand");
        // ADDED (the polygon), then an empty OTHER_STRUCTURE_CHANGE: same hierarchy, second snapshot
        long expand = command("hand+polygon", "hand+polygon");
        qupath.undo();
        assertTrue(follow().isEmpty(), "Ctrl+Z #1 restores the same hierarchy: nothing is undone");
        assertEquals(ActionHistory.ACTIVE, history.state(expand));
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(expand), "Ctrl+Z #2 removes the polygon");
        assertEquals(ActionHistory.ACTIVE, history.state(hand));
    }

    @Test
    void anActionWhoseResultQuPathStillHoldsIsNotAbandoned() {
        command("hand");
        long expand = command("hand+polygon", "hand+polygon");
        qupath.undo(); qupath.undo();
        follow();
        qupath.redo();                  // back to the polygon: the first of its two identical snapshots
        follow();
        assertEquals(ActionHistory.ACTIVE, history.state(expand));
        long drawn = command("hand+polygon+new"); // drops the trailing identical snapshot from the Redo
        assertEquals(ActionHistory.ACTIVE, history.state(expand), "the polygon is in the hierarchy");
        assertEquals(ActionHistory.ACTIVE, history.state(drawn));
        qupath.undo();
        follow();
        assertEquals(ActionHistory.ACTIVE, history.state(expand));
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(expand));
    }

    // A tiled detection's first snapshot holds its ParallelTileObjects, which QuPath 0.7.0 cannot
    // deserialize (no public no-arg constructor): that Undo fails, the hierarchy stays, the line moves.
    static final String TILES = "hand+qupath.lib.plugins.ParallelTileObject";

    @Test
    void anUndoQuPathCouldNotRestoreLeavesTheActionInForce() {
        long hand = command("hand");
        long detection = command(TILES, "hand+cells");
        qupath.undo();                  // fails: "Error deserializing object", the 79 cells stay
        assertTrue(follow().isEmpty());
        assertEquals(ActionHistory.ACTIVE, history.state(detection));
        qupath.undo();                  // the cells go
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(detection));
        assertEquals(ActionHistory.ACTIVE, history.state(hand));
        qupath.redo();                  // fails again: the hierarchy stays without cells
        assertTrue(follow().isEmpty());
        qupath.redo();
        follow();
        assertEquals(ActionHistory.ACTIVE, history.state(detection));
    }

    @Test
    void aNewChangeAfterAFailedUndoKeepsWhatTheHierarchyHolds() {
        command("hand");
        long detection = command(TILES, "hand+cells");
        qupath.undo();                  // fails, the cells stay
        follow();
        long drawn = command("hand+cells+new"); // its snapshot is taken from the hierarchy with the cells
        assertEquals(ActionHistory.ACTIVE, history.state(detection), "the cells are in the hierarchy");
        qupath.undo();                  // back onto the tiles snapshot: fails, nothing changes
        follow();
        assertEquals(ActionHistory.ACTIVE, history.state(drawn));
        assertEquals(ActionHistory.ACTIVE, history.state(detection));
        qupath.undo();                  // "hand": both go at once
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(drawn));
        assertEquals(ActionHistory.UNDONE, history.state(detection));
    }

    @Test
    void aDragOfManyFramesIsUndoneAtTheFirstFrameBack() {
        long draw = command("a@0");
        long drag = command("a@1", "a@2", "a@3", "a@4", "a@5");
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(drag));
        for (int i = 0; i < 4; i++) { qupath.undo(); assertTrue(follow().isEmpty()); }
        assertEquals(ActionHistory.ACTIVE, history.state(draw));
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(draw));
    }

    @Test
    void redoBringsTheActionBackOnlyWithItsResult() {
        long detection = command("empty", "cells");
        qupath.undo(); qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(detection));
        qupath.redo();
        assertTrue(follow().isEmpty(), "half way: still undone");
        qupath.redo();
        List<CaptureEvents.ActionChange> changes = follow();
        assertEquals(1, changes.size());
        assertEquals(CaptureEvents.ActionChange.REDONE, changes.get(0).change());
        assertEquals(List.of(detection), changes.get(0).actions());
        assertEquals(ActionHistory.ACTIVE, history.state(detection));
    }

    @Test
    void ctrlZOnAnEmptyStackChangesNothing() {
        assertFalse(qupath.undo());
        assertTrue(follow().isEmpty());
        long a = command("a");
        qupath.undo();
        follow();
        assertFalse(qupath.undo());
        assertTrue(follow().isEmpty());
        assertEquals(ActionHistory.UNDONE, history.state(a));
    }

    @Test
    void aNewSnapshotAfterUndoAbandonsTheRedoBranch() {
        long a = command("a");
        long b = command("a+b");
        qupath.undo();
        follow();
        long c = command("a+c");
        assertEquals(ActionHistory.ABANDONED, history.state(b));
        assertEquals(ActionHistory.ACTIVE, history.state(a));
        assertEquals(ActionHistory.ACTIVE, history.state(c));
        assertFalse(qupath.redo());
    }

    @Test
    void undoThenNewSnapshotSeenTogetherStillAbandons() {
        command("a");
        long b = command("a+b");
        qupath.undo();
        qupath.snap("a+c"); // no follow() in between: a script did both
        List<CaptureEvents.ActionChange> changes = follow();
        assertEquals(ActionHistory.ABANDONED, history.state(b));
        assertEquals(List.of(CaptureEvents.ActionChange.UNDONE, CaptureEvents.ActionChange.ABANDONED),
            changes.stream().map(CaptureEvents.ActionChange::change).toList());
    }

    @Test
    void anActionWithoutSnapshotDoesNotEndTheRedoBranch() {
        command("a");
        long b = command("a+b");
        qupath.undo();
        follow();
        long imageType = command();
        assertEquals(ActionHistory.UNDONE, history.state(b));
        qupath.redo();
        follow();
        assertEquals(ActionHistory.ACTIVE, history.state(b));
        assertEquals(ActionHistory.ACTIVE, history.state(imageType));
    }

    @Test
    void anActionPushedOutOfTheStackStaysInForce() {
        qupath.cap = 2;
        long a = command("a");
        command("a+b");
        command("a+b+c");
        command("a+b+c+d"); // a's snapshot leaves QuPath's stack
        assertEquals(ActionHistory.ACTIVE, history.state(a));
        while (qupath.undo()) follow();
        assertEquals(ActionHistory.ACTIVE, history.state(a), "QuPath can no longer undo it");
    }

    @Test
    void anUndoneActionDroppedWithTheStackIsAbandoned() {
        long a = command("a");
        qupath.undo();
        follow();
        // the hierarchy grew above maxUndoHierarchySize: QuPath has no line any more
        assertEquals(List.of(CaptureEvents.ActionChange.ABANDONED),
            history.follow(List.of(), -1, true).stream().map(CaptureEvents.ActionChange::change).toList());
        assertEquals(ActionHistory.ABANDONED, history.state(a));
    }

    @Test
    void aHandDrawingIsOneActionWithTheStepCapturedAfterItsDebounce() {
        long drawn = history.drawing();             // ADDED on the FX thread, noted by the hierarchy listener
        qupath.snap("a");
        follow();
        history.seal();                             // pulses go by during the 400 ms debounce
        assertFalse(history.hasCommand(drawn), "no command captured it: a hand drawing");
        assertEquals(drawn, history.capturedIn(drawn)); // the "Manual annotation" step
        history.seal();
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(drawn));
    }

    @Test
    void twoDrawingsWithinTheDebounceAreTwoActions() {
        long first = history.drawing();
        qupath.snap("a");
        follow();
        long second = history.drawing();            // less than 400 ms later: the first is not captured yet
        qupath.snap("a+b");
        follow();
        assertNotEquals(first, second);
        history.capturedIn(first);
        history.capturedIn(second);
        history.seal();
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(second));
        assertEquals(ActionHistory.ACTIVE, history.state(first), "Ctrl+Z undoes the last drawing, not the one before");
    }

    @Test
    void aCommandRunWithinADrawingsDebounceIsAnActionOfItsOwn() {
        long drawn = history.drawing();
        qupath.snap("a");
        follow();
        history.beyondDrawing();                    // the plugin's hierarchy events, off the FX thread
        qupath.snap("a+cells");
        follow();
        long command = history.captured(0);         // its step
        history.command(command);
        history.seal();
        assertNotEquals(drawn, command);
        assertFalse(history.hasCommand(drawn), "the drawing keeps its own step");
        history.capturedIn(drawn);
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(command));
        assertEquals(ActionHistory.ACTIVE, history.state(drawn));
    }

    @Test
    void aStepWithoutHierarchyEventWithinADrawingsDebounceIsNotTheDrawings() {
        long drawn = history.drawing();
        qupath.snap("a");
        follow();
        long imageType = history.captured(0);       // setImageType, another pulse
        assertNotEquals(drawn, imageType);
        assertFalse(history.hasCommand(drawn));
    }

    @Test
    void aCommandThatAddsAnAnnotationOnTheFxThreadOwnsIt() {
        long added = history.drawing();             // ADDED on the FX thread, same call as the command's step
        long command = history.captured(added);     // same pulse: the same gesture
        history.command(command);
        assertEquals(added, command);
        assertTrue(history.hasCommand(added));
    }

    @Test
    void anAnnotationAddedByACommandBelongsToTheCommand() {
        long added = history.current();             // the plugin's ADDED
        qupath.snap("polygon");
        follow();
        long command = history.captured();          // "Expand annotations"
        history.command(command);
        history.seal();
        assertEquals(added, command);
        assertTrue(history.hasCommand(added), "a command captured it: not a hand drawing");
    }

    @Test
    void twoAnnotationsPastedTogetherAreBothDrawnByHand() {
        long added = history.current();             // one ADDED event, two annotations
        history.captured();                         // the first one's "Manual annotation" step
        assertFalse(history.hasCommand(added), "the other one was not made by a command either");
    }

    @Test
    void aDeletionJustBeforeAPauseCanStillBeUndone() {
        long drawn = command("a");
        long deletion = history.beyondDrawing();    // the annotation's step retired, no step of its own yet
        List<String> effects = new ArrayList<>();
        history.supersede(deletion, () -> effects.add("step back"), () -> effects.add("retired again"));
        qupath.snap("empty");
        follow();
        history.dropOpen();                         // capture paused
        qupath.undo();
        follow();
        assertEquals(List.of("step back"), effects);
        assertEquals(ActionHistory.ACTIVE, history.state(drawn));
    }

    @Test
    void aNewSnapshotHoldingTilesIsNoFailedRestore() {
        long hand = command("hand");
        // a pulse while the detection runs: its tiles are in the hierarchy, the snapshot is new
        history.beyondDrawing();
        qupath.snap(TILES);
        assertTrue(follow().isEmpty(), "no Undo happened");
        qupath.snap("hand+cells");
        assertTrue(follow().isEmpty());
        long detection = history.captured(0);
        history.seal();
        assertEquals(ActionHistory.ACTIVE, history.state(detection));
        assertEquals(ActionHistory.ACTIVE, history.state(hand));
    }

    @Test
    void aLineReadBeforeANewerOneNeverUndoesWhatIsInForce() throws Exception {
        command("a");
        // Two threads follow at once: the first reads its line, the second snaps and follows before it applies.
        java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch newer = new java.util.concurrent.CountDownLatch(1);
        Thread slow = new Thread(() -> history.follow(() -> {
            UndoLine.Line l = new UndoLine.Line(qupath.line(), qupath.position());
            read.countDown();
            try { newer.await(300, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { }
            return l;
        }, true, true));
        slow.start();
        read.await();
        Thread fast = new Thread(() -> {
            synchronized (qupath) { qupath.snap("a+b"); }
            history.follow(() -> new UndoLine.Line(qupath.line(), qupath.position()), true, true);
            newer.countDown();
        });
        fast.start();
        slow.join();
        fast.join();
        long b = history.captured(0);
        follow();
        assertEquals(ActionHistory.ACTIVE, history.state(b), "the older line came first or not at all");
        assertTrue(history.notInForce().isEmpty());
    }

    @Test
    void aFollowWithoutTransitionsOnlyClaimsSnapshots() {
        long a = command("a");
        qupath.undo();
        qupath.snap("a2");                          // Undo then a new change, seen first by a plugin thread
        assertTrue(history.follow(() -> new UndoLine.Line(qupath.line(), qupath.position()), true, false).isEmpty());
        long b = history.captured(0);
        history.seal();
        follow();                                   // the FX pulse: a was undone and its Redo dropped
        assertEquals(ActionHistory.ABANDONED, history.state(a));
        assertEquals(ActionHistory.ACTIVE, history.state(b));
    }

    @Test
    void snapshotsSeenWhileCaptureIsPausedBelongToNoAction() {
        long a = command("a");
        history.dropOpen();
        qupath.snap("a+paused");
        history.follow(qupath.line(), qupath.position(), false);
        history.dropOpen();
        long b = command("a+paused+b");
        qupath.undo();
        follow();
        assertEquals(ActionHistory.UNDONE, history.state(b));
        qupath.undo(); // the paused change
        assertTrue(follow().isEmpty());
        assertEquals(ActionHistory.ACTIVE, history.state(a));
    }

    @Test
    void aRecordCarriesItsActionAndWhatQuPathDidToIt() {
        qupath.snap("a");
        follow();
        long a = history.captured();
        JsonObject step = new JsonObject();
        step.addProperty("command", "Manual annotation: Rectangle");
        history.hold(a, step);
        history.seal();
        assertEquals(a, step.get("action").getAsLong());
        assertFalse(step.has("undo"));

        qupath.undo();
        follow();
        assertEquals("undone", step.getAsJsonObject("undo").get("state").getAsString());
        assertTrue(step.getAsJsonObject("undo").has("at"));

        qupath.redo();
        follow();
        assertFalse(step.has("undo"), "redone: in force again");

        qupath.undo();
        follow();
        command("b");
        assertEquals("abandoned", step.getAsJsonObject("undo").get("state").getAsString());
        assertTrue(step.getAsJsonObject("undo").has("at"));
        assertTrue(step.getAsJsonObject("undo").has("abandoned_at"));
    }

    @Test
    void aRecordHeldByAnActionAlreadyUndoneIsMarkedAtOnce() {
        // a replayed instruction whose snapshot the user undid before it was captured
        qupath.snap("replayed");
        follow();
        qupath.undo();
        follow();
        long n = history.captured();
        JsonObject step = new JsonObject();
        history.hold(n, step);
        assertEquals(ActionHistory.UNDONE, history.state(n));
        assertEquals("undone", step.getAsJsonObject("undo").get("state").getAsString());
    }

    @Test
    void whatAnActionRetiredComesBackWhenItIsUndone() {
        long draw = command("a@0");
        qupath.snap("a@1");
        follow();
        long move = history.captured();
        List<String> effects = new ArrayList<>();
        history.supersede(move, () -> effects.add("old step back"), () -> effects.add("old step retired"));
        history.seal();
        qupath.undo();
        follow();
        assertEquals(List.of("old step back"), effects);
        qupath.redo();
        follow();
        assertEquals(List.of("old step back", "old step retired"), effects);
        assertEquals(ActionHistory.ACTIVE, history.state(draw));
    }

    @Test
    void theActionsNotInForceAreKeptForAWindowOpenedLater() {
        long a = command("a");
        long b = command("a+b");
        qupath.undo();
        follow();
        assertEquals(java.util.Map.of(b, ActionHistory.UNDONE), history.notInForce());
        history.forget(); // Reset
        assertTrue(history.notInForce().isEmpty());
        qupath.undo();
        assertTrue(follow().isEmpty(), "after Reset an Undo changes nothing in the record");
        assertEquals(ActionHistory.ACTIVE, history.state(a));
    }

    @Test
    void afterACrashAnUndoneStepComesBackAbandonedAndNumbersGoOn() {
        JsonObject kept = new JsonObject();
        kept.addProperty("action", 4000);
        JsonObject undone = new JsonObject();
        undone.addProperty("action", 4001);
        JsonObject u = new JsonObject();
        u.addProperty("state", "undone");
        u.addProperty("at", "2026-11-02T10:00:00Z");
        undone.add("undo", u);
        history.restore(List.of(kept, undone));
        assertEquals("abandoned", undone.getAsJsonObject("undo").get("state").getAsString());
        assertEquals("2026-11-02T10:00:00Z", undone.getAsJsonObject("undo").get("at").getAsString());
        assertEquals(ActionHistory.ABANDONED, history.state(4001));
        assertTrue(history.captured() > 4001);
    }
}
