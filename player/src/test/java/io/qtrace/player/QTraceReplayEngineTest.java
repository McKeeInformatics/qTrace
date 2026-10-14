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

package io.qtrace.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QTraceReplayEngineTest {

    // ── display_settings[] → executable ReplayStep ──────────────────────────────
    // Unlike measurement_maps[] (display-only, never replayed — see
    // QTraceReplayEngine#extractMeasurementMapSteps), display_settings[] entries carry
    // scriptable:true and must come back from extractSteps() as real, runnable steps.

    private static final String QTRACE_JSON = """
        {
          "sessions": [
            {
              "steps": [],
              "display_settings": [
                {
                  "channels": [
                    {"name": "Eu(151)_151Eu_FN (C14)", "color_rgb": 65535, "min": 0.879, "max": 9.94, "selected": false},
                    {"name": "Nd(146)_146Nd_S100b (C9)", "min": 1.0, "max": 20.0, "selected": true}
                  ],
                  "gamma": 1.2,
                  "grayscale": false,
                  "invert_background": true,
                  "timestamp": "2026-09-10T17:32:46Z",
                  "scriptable": true
                }
              ]
            }
          ]
        }
        """;

    @Test
    void extractSteps_includesExecutableDisplaySettingsStep() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(QTRACE_JSON, -1);

        assertThat(steps).hasSize(1);
        ReplayStep step = steps.get(0);
        assertThat(step.groovyFragment()).isNotBlank();
        assertThat(step.type()).isEqualTo("Display settings");
    }

    @Test
    void extractSteps_displaySettingsGroovy_setsPerChannelDisplayAndSelection() {
        ReplayStep step = QTraceReplayEngine.extractSteps(QTRACE_JSON, -1).get(0);
        String groovy = step.groovyFragment();

        assertThat(groovy).contains("getImageDisplay()");
        assertThat(groovy).contains("Eu(151)_151Eu_FN (C14)");
        assertThat(groovy).contains("setMinMaxDisplay");
        assertThat(groovy).contains("0.879");
        assertThat(groovy).contains("9.94");
        assertThat(groovy).contains("setChannelSelected");
        assertThat(groovy).contains("setUseGrayscaleLuts(false)");
        assertThat(groovy).contains("setUseInvertedBackground(true)");
        assertThat(groovy).contains("setGamma(1.2)");
    }

    @Test
    void extractSteps_noDisplaySettings_noExtraStep() {
        String json = """
            { "sessions": [ { "steps": [] } ] }
            """;
        assertThat(QTraceReplayEngine.extractSteps(json, -1)).isEmpty();
    }

    // ── stamped / unstamped instructions ────────────────────────────────────────
    // An instruction is validated as soon as it appears in at least one stamped session;
    // one recorded only in unstamped (autosaved) sessions is flagged, and can be left out.

    private static final String MIXED_JSON = """
        {
          "sessions": [
            { "session_id": "aaaaaaaa-1", "validation": {"validator": "Jane"}, "steps": [
                {"script_fragment": "stepA()", "is_scriptable": true} ] },
            { "session_id": "bbbbbbbb-2", "validation": null, "validation_state": "unstamped", "steps": [
                {"script_fragment": "stepA()", "is_scriptable": true},
                {"script_fragment": "stepB()", "is_scriptable": true} ] }
          ]
        }
        """;

    @Test
    void stepsAreValidatedOnlyIfSomeStampedSessionHasThem() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(MIXED_JSON, -1);
        assertThat(steps).extracting(ReplayStep::groovyFragment).containsExactly("stepA()", "stepB()");
        assertThat(steps).extracting(ReplayStep::validated).containsExactly(true, false);
        assertThat(steps.get(1).sessionId()).isEqualTo("bbbbbbbb-2");
    }

    @Test
    void laterStampValidatesEarlierUnstampedWork() {
        String json = """
            { "sessions": [
                { "session_id": "u1", "validation_state": "unstamped", "steps": [
                    {"script_fragment": "stepB()", "is_scriptable": true} ] },
                { "session_id": "s2", "validation_state": "stamped", "validation": {"validator": "Jane"}, "steps": [
                    {"script_fragment": "stepB()", "is_scriptable": true} ] } ] }
            """;
        assertThat(QTraceReplayEngine.extractSteps(json, -1)).extracting(ReplayStep::validated).containsExactly(true);
    }

    @Test
    void validatedOnlyLeavesUnstampedInstructionsOut() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(MIXED_JSON, -1, true);
        assertThat(steps).extracting(ReplayStep::groovyFragment).containsExactly("stepA()");
        assertThat(steps.get(0).index()).isEqualTo(1);
    }

    @Test
    void generatedScriptFlagsUnstampedInstructions() {
        String script = QTraceReplayEngine.generateGroovyScript(MIXED_JSON, null, -1);
        assertThat(script).contains("// [unstamped] session bbbbbbbb");
        assertThat(script.indexOf("// [unstamped]")).isGreaterThan(script.indexOf("stepA()"));
    }

    @Test
    void validatedOnlyScriptOmitsUnstampedInstructions() {
        String script = QTraceReplayEngine.generateGroovyScript(MIXED_JSON, null, -1, true);
        assertThat(script).contains("stepA()").doesNotContain("stepB()").doesNotContain("[unstamped]");
    }

    // ── instructions the author took out of the replay ──────────────────────────
    // They stay in the .qtrace (replay_skip) and are never run. The choice follows the
    // instruction's last occurrence in the range replayed, like the dedup itself.

    private static final String SKIP_JSON = """
        {
          "sessions": [
            { "session_id": "s1", "validation_state": "stamped", "validation": {"validator": "Jane"}, "steps": [
                {"script_fragment": "stepA()", "is_scriptable": true},
                {"script_fragment": "stepB()", "is_scriptable": true,
                 "replay_skip": true, "replay_skip_by": "Bob", "replay_skip_at": "2026-09-30T14:02:00Z"},
                {"script_fragment": "stepC()", "is_scriptable": true} ] }
          ]
        }
        """;

    @Test
    void skippedInstructionIsListedButNeverExecutable() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(SKIP_JSON, -1);
        assertThat(steps).extracting(ReplayStep::groovyFragment).containsExactly("stepA()", "stepB()", "stepC()");
        assertThat(steps).extracting(ReplayStep::skipped).containsExactly(false, true, false);
        assertThat(steps).extracting(ReplayStep::isExecutable).containsExactly(true, false, true);
    }

    @Test
    void withoutSkippedRenumbersWhatIsLeft() {
        List<ReplayStep> steps = QTraceReplayEngine.withoutSkipped(QTraceReplayEngine.extractSteps(SKIP_JSON, -1));
        assertThat(steps).extracting(ReplayStep::groovyFragment).containsExactly("stepA()", "stepC()");
        assertThat(steps).extracting(ReplayStep::index).containsExactly(1, 2);
    }

    @Test
    void generatedScriptNeverContainsASkippedInstruction() {
        String script = QTraceReplayEngine.generateGroovyScript(SKIP_JSON, null, -1);
        assertThat(script).contains("stepA()").contains("stepC()").doesNotContain("stepB()");
        assertThat(script).contains("Step 2 start").doesNotContain("Step 3 start");
    }

    @Test
    void assembledScriptDropsASkippedInstructionEvenIfHandedOne() {
        String script = QTraceReplayEngine.assembleScript("", QTraceReplayEngine.extractSteps(SKIP_JSON, -1));
        assertThat(script).contains("stepA()").doesNotContain("stepB()");
    }

    @Test
    void theLastOccurrenceDecidesWhetherAnInstructionIsSkipped() {
        String putBack = """
            { "sessions": [
                { "session_id": "u1", "validation_state": "unstamped", "steps": [
                    {"script_fragment": "stepB()", "is_scriptable": true, "replay_skip": true} ] },
                { "session_id": "u2", "validation_state": "unstamped", "steps": [
                    {"script_fragment": "stepB()", "is_scriptable": true} ] } ] }
            """;
        assertThat(QTraceReplayEngine.extractSteps(putBack, -1)).extracting(ReplayStep::skipped).containsExactly(false);
        assertThat(QTraceReplayEngine.extractSteps(putBack, 0)).extracting(ReplayStep::skipped).containsExactly(true);

        String takenOutLater = """
            { "sessions": [
                { "session_id": "s1", "validation_state": "stamped", "validation": {"validator": "Jane"}, "steps": [
                    {"script_fragment": "stepB()", "is_scriptable": true} ] },
                { "session_id": "u2", "validation_state": "unstamped", "steps": [
                    {"script_fragment": "stepB()", "is_scriptable": true, "replay_skip": true} ] } ] }
            """;
        assertThat(QTraceReplayEngine.extractSteps(takenOutLater, -1)).extracting(ReplayStep::skipped).containsExactly(true);
        // The certified session was stamped with the instruction in: it still replays it.
        assertThat(QTraceReplayEngine.extractSteps(takenOutLater, 0)).extracting(ReplayStep::skipped).containsExactly(false);
    }

    @Test
    void traceWithoutAnyChoiceReplaysEverything() {
        assertThat(QTraceReplayEngine.extractSteps(MIXED_JSON, -1)).extracting(ReplayStep::skipped).containsOnly(false);
    }

    @Test
    void preflightIgnoresModelsOnlyUsedBySkippedInstructions() {
        String json = """
            { "sessions": [ { "steps": [
                {"script_fragment": "InstanSeg.builder().modelPath(\\"/m/fluorescence_nuclei_and_cells-0.1.1\\").build()",
                 "is_scriptable": true, "replay_skip": true} ] } ] }
            """;
        assertThat(QTraceReplayEngine.extractPreflightInfo(json).modelPaths()).isEmpty();
        assertThat(QTraceReplayEngine.extractPreflightInfo(json.replace(", \"replay_skip\": true", "")).modelPaths())
            .isNotEmpty();
    }

    // ── Certified session: a qtc_… certificate covers one stamped session, not the image's history ──

    private static final String HISTORY_JSON = """
        { "sessions": [
            { "session_id": "s1", "validation_state": "stamped", "validation": {"validator": "Jane"}, "steps": [
                {"script_fragment": "setImageType('FLUORESCENCE')", "is_scriptable": true} ] },
            { "session_id": "u2", "validation_state": "unstamped", "steps": [
                {"script_fragment": "classify()", "is_scriptable": true} ] },
            { "session_id": "s3", "validation_state": "stamped", "validation": {"validator": "Jane"}, "steps": [
                {"script_fragment": "setImageType('FLUORESCENCE')", "is_scriptable": true},
                {"script_fragment": "runTmaDearrayer()", "is_scriptable": true} ] },
            { "session_id": "u4", "validation_state": "unstamped", "steps": [
                {"script_fragment": "afterStamp()", "is_scriptable": true} ] } ] }
        """;

    @Test
    void certifiedSessionIsTheLastStampedOne() {
        assertThat(QTraceReplayEngine.certifiedSessionIndex(HISTORY_JSON)).isEqualTo(2);
    }

    @Test
    void certifiedSessionIsMinusOneWithoutAnyStamp() {
        String json = """
            { "sessions": [ { "session_id": "u1", "validation_state": "unstamped", "steps": [] } ] }
            """;
        assertThat(QTraceReplayEngine.certifiedSessionIndex(json)).isEqualTo(-1);
    }

    @Test
    void replayingTheCertifiedSessionLeavesTheRestOfTheHistoryOut() {
        int idx = QTraceReplayEngine.certifiedSessionIndex(HISTORY_JSON);
        assertThat(QTraceReplayEngine.extractSteps(HISTORY_JSON, idx))
            .extracting(ReplayStep::groovyFragment)
            .containsExactly("setImageType('FLUORESCENCE')", "runTmaDearrayer()");
    }

    @Test
    void onlyCertificateSourcesAreScopedToTheCertifiedSession() {
        assertThat(QTraceReplayEngine.isCertificateSource("qtc_01M3G5V194X350P1ESRD8VJJAA")).isTrue();
        assertThat(QTraceReplayEngine.isCertificateSource("https://www.qtrace.ca/verify/qtc_01ABC")).isTrue();
        assertThat(QTraceReplayEngine.isCertificateSource("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV")).isTrue();
        assertThat(QTraceReplayEngine.isCertificateSource("/home/me/project/image.qtrace")).isFalse();
    }

    // ── class_list[] → executable steps, played before the instructions ─────────

    private static final String CLASS_LIST_JSON = """
        {
          "sessions": [
            {
              "session_id": "s1",
              "steps": [
                {"script_fragment": "runPlugin('qupath.X', '{}')", "is_scriptable": true}
              ],
              "class_list": [
                {"action": "add", "name": "Training_Dataset", "color_rgb": 65280, "timestamp": "2026-10-04T10:00:00Z", "scriptable": true},
                {"action": "add", "name": "It's: Positive", "timestamp": "2026-10-04T10:00:05Z", "scriptable": true},
                {"action": "remove", "name": "Necrosis", "color_rgb": 3289650, "timestamp": "2026-10-04T10:00:09Z", "scriptable": true}
              ]
            }
          ]
        }
        """;

    @Test
    void extractSteps_classListComesFirstInTheOrderItWasMade() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(CLASS_LIST_JSON, -1);

        assertThat(steps).extracting(ReplayStep::type).containsExactly("Class list", "Class list", "Class list", ReplayStep.classify("runPlugin('qupath.X', '{}')"));
        assertThat(steps).extracting(ReplayStep::index).containsExactly(1, 2, 3, 4);
        assertThat(steps).extracting(ReplayStep::title)
            .startsWith("Add class — Training_Dataset", "Add class — It's: Positive", "Remove class — Necrosis");
        assertThat(steps.get(0).sessionId()).isEqualTo("s1");
    }

    @Test
    void extractSteps_addClassGroovy_createsTheClassWithItsColourAndPutsItInTheList() {
        String groovy = QTraceReplayEngine.extractSteps(CLASS_LIST_JSON, -1).get(0).groovyFragment();

        assertThat(groovy).contains("getPathClass('Training_Dataset', 65280)");
        assertThat(groovy).contains("getAvailablePathClasses()");
        assertThat(groovy).contains(".add(");
        assertThat(groovy).contains("Platform.runLater");
    }

    @Test
    void extractSteps_addClassGroovy_withoutColourAndWithAQuoteInTheName() {
        String groovy = QTraceReplayEngine.extractSteps(CLASS_LIST_JSON, -1).get(1).groovyFragment();

        assertThat(groovy).contains("getPathClass('It\\'s: Positive')");
        assertThat(groovy).doesNotContain("getPathClass('It\\'s: Positive',");
    }

    @Test
    void extractSteps_removeClassGroovy_takesItOutOfTheList() {
        String groovy = QTraceReplayEngine.extractSteps(CLASS_LIST_JSON, -1).get(2).groovyFragment();

        assertThat(groovy).contains("getPathClass('Necrosis')");
        assertThat(groovy).contains(".remove(");
        assertThat(groovy).doesNotContain(".add(");
    }

    @Test
    void extractSteps_aTraceWithoutClassListIsUnchanged() {
        assertThat(QTraceReplayEngine.extractSteps(QTRACE_JSON, -1)).hasSize(1);
    }

    // ── An instruction is listed under the name of its step ──────────────────────

    @Test
    void anInstructionCarriesTheNameOfItsStep_notTheFirstLineOfItsScript() {
        String json = """
            { "sessions": [
              { "session_id": "s1", "steps": [
                { "command": "Set image type", "is_scriptable": true, "script_fragment": "setImageType('FLUORESCENCE')" },
                { "command": "Script: Otsu.groovy", "is_scriptable": true, "script_fragment": "/* ===== */\nrunOtsu()" } ] },
              { "session_id": "s2", "steps": [
                { "command": "Script: Otsu v2.groovy", "is_scriptable": true, "script_fragment": "/* ===== */\nrunOtsu()" },
                { "is_scriptable": true, "script_fragment": "// no name\nunnamed()" } ] } ] }""";
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(json, -1);
        // The last occurrence names it; a step without a name keeps the first line of its script.
        assertThat(steps.stream().map(ReplayStep::title).toList())
            .containsExactly("Set image type", "Script: Otsu v2.groovy", "// no name");
        assertThat(QTraceReplayEngine.extractSteps(json, 0).get(1).title()).isEqualTo("Script: Otsu.groovy");
    }

    // ── qualified names of a script ─────────────────────────────────────────────
    // A recorded script keeps its own imports: only the body's qualified names are shortened.

    @Test
    void extractSteps_scriptImports_areLeftAsWritten() {
        String json = """
            {"sessions": [{"steps": [{
              "command": "Script: snr.groovy", "is_scriptable": true,
              "script_fragment": "import qupath.imagej.tools.IJTools\\n  import qupath.fx.dialogs.Dialogs\\ndef ip = qupath.imagej.tools.IJTools.convertToImagePlus(server, region)"
            }]}]}
            """;
        String groovy = QTraceReplayEngine.extractSteps(json, -1).get(0).groovyFragment();
        assertThat(groovy).contains("import qupath.imagej.tools.IJTools\n");
        assertThat(groovy).contains("  import qupath.fx.dialogs.Dialogs\n");
        assertThat(groovy).contains("def ip = IJTools.convertToImagePlus(server, region)");
    }
}
