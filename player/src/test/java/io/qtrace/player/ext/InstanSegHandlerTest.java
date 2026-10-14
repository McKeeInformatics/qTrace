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

package io.qtrace.player.ext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InstanSegHandlerTest {

    private final InstanSegHandler handler = new InstanSegHandler();

    // ── buildPreambleGroovy ───────────────────────────────────────────────────

    @Test
    void buildPreambleGroovy_containsHelperClosures() {
        String preamble = handler.buildPreambleGroovy();
        assertThat(preamble).contains("_qtraceInstanSegPath");
        assertThat(preamble).contains("ai.djl.offline");
    }

    @Test
    void buildPreambleGroovy_warnsOnVersionMismatch() {
        // A base-name-only match (fallback when the exact requested version isn't found locally)
        // must be flagged as a version mismatch, not reported the same way as an exact match —
        // see isPrerequisiteMet_differentVersionOnDiskDoesNotCount for the pre-flight side of this.
        String preamble = handler.buildPreambleGroovy();
        assertThat(preamble).contains("_qtraceInstanSegFindExactInRoot");
        assertThat(preamble.toLowerCase()).contains("different version");
    }

    @Test
    void buildPreambleGroovy_balancedBraces() {
        String preamble = handler.buildPreambleGroovy();
        long open  = preamble.chars().filter(c -> c == '{').count();
        long close = preamble.chars().filter(c -> c == '}').count();
        assertThat(open).isEqualTo(close);
    }

    // ── rewriteStepGroovy ─────────────────────────────────────────────────────

    // The rewritten call keeps the original path as a fallback second argument —
    // the helper closure resolves the model locally first, then tries that path.

    @Test
    void rewriteStepGroovy_absoluteWindowsPath() {
        String frag = ".modelPath(\"C:/foo/bar/fluorescence_nuclei_and_cells-0.1.0\")";
        String result = handler.rewriteStepGroovy(frag);
        assertThat(result).contains(
            "_qtraceInstanSegPath(\"fluorescence_nuclei_and_cells\", \"C:/foo/bar/fluorescence_nuclei_and_cells-0.1.0\")");
        assertThat(result).doesNotContain(".modelPath(\"C:/foo");
    }

    @Test
    void rewriteStepGroovy_absoluteUnixPath() {
        String frag = ".modelPath(\"/home/user/qupath/instanseg/cell_detection-2.0.0\")";
        String result = handler.rewriteStepGroovy(frag);
        assertThat(result).contains(
            "_qtraceInstanSegPath(\"cell_detection\", \"/home/user/qupath/instanseg/cell_detection-2.0.0\")");
    }

    @Test
    void rewriteStepGroovy_noVersionSuffix() {
        String frag = ".modelPath(\"foo_model\")";
        String result = handler.rewriteStepGroovy(frag);
        assertThat(result).contains("_qtraceInstanSegPath(\"foo_model\", \"foo_model\")");
    }

    @Test
    void rewriteStepGroovy_versionStripping() {
        String frag = ".modelPath(\"mymodel-v2.0.0\")";
        String result = handler.rewriteStepGroovy(frag);
        assertThat(result).contains("_qtraceInstanSegPath(\"mymodel\", \"mymodel-v2.0.0\")");
    }

    @Test
    void rewriteStepGroovy_noModelPath_unchanged() {
        String frag = "InstanSeg.builder().device(\"cpu\").build().detectObjects()";
        assertThat(handler.rewriteStepGroovy(frag)).isEqualTo(frag);
    }

    // ── isPrerequisiteMet ─────────────────────────────────────────────────────

    @Test
    void isPrerequisiteMet_notPresent(@TempDir Path tmpHome) {
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            assertThat(handler.isPrerequisiteMet("fluorescence_nuclei_and_cells")).isFalse();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void isPrerequisiteMet_emptyModelDirDoesNotCount(@TempDir Path tmpHome) {
        // Directory created but rdf.yaml missing — an interrupted download must not
        // be reported as a satisfied prerequisite.
        tmpHome.resolve("QuPath/v0.7/instanseg/downloaded/cell_detection-2.0.0").toFile().mkdirs();
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            assertThat(handler.isPrerequisiteMet("cell_detection")).isFalse();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void isPrerequisiteMet_neverMatchesTheCapturingMachinesLiteralPath(@TempDir Path tmpHome) throws Exception {
        // originalPath is whatever directory the CAPTURING machine's InstanSeg used — it must never
        // be checked as a filesystem location on THIS machine. Matching is by model name against
        // roots discovered locally, regardless of whether that literal path happens to exist here.
        File captured = tmpHome.resolve("elsewhere/some_model-1.0.0").toFile();
        captured.mkdirs();
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            assertThat(handler.isPrerequisiteMet("some_model", captured.getAbsolutePath())).isFalse();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void isPrerequisiteMet_modelDirPresent(@TempDir Path tmpHome) throws Exception {
        // A model directory only counts as present when it actually holds the
        // model descriptor — an empty directory is a failed/partial download.
        File modelDir = tmpHome.resolve("QuPath/v0.7/instanseg/downloaded/fluorescence_nuclei_and_cells-0.1.0").toFile();
        modelDir.mkdirs();
        new File(modelDir, "rdf.yaml").createNewFile();
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            assertThat(handler.isPrerequisiteMet("fluorescence_nuclei_and_cells")).isTrue();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void isPrerequisiteMet_differentVersionOnDiskStillCounts(@TempDir Path tmpHome) throws Exception {
        // Only fluorescence_nuclei_and_cells-0.1.1 is downloaded locally, but the captured
        // script recorded -0.1.0 — still usable (isPrerequisiteMet is a boolean "can we run"
        // signal), but must surface as VERSION_MISMATCH, not silently PRESENT — see
        // getModelStatus_differentVersionOnDiskIsFlaggedAsMismatch.
        File modelDir = tmpHome.resolve("QuPath/v0.7/instanseg/downloaded/fluorescence_nuclei_and_cells-0.1.1").toFile();
        modelDir.mkdirs();
        new File(modelDir, "rdf.yaml").createNewFile();
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            String originalPath = "C:/Apps/Software/InstanceSeg/downloaded/fluorescence_nuclei_and_cells-0.1.0";
            assertThat(handler.isPrerequisiteMet("fluorescence_nuclei_and_cells", originalPath)).isTrue();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void isPrerequisiteMet_exactVersionOnDiskCounts(@TempDir Path tmpHome) throws Exception {
        File modelDir = tmpHome.resolve("QuPath/v0.7/instanseg/downloaded/fluorescence_nuclei_and_cells-0.1.0").toFile();
        modelDir.mkdirs();
        new File(modelDir, "rdf.yaml").createNewFile();
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            String originalPath = "C:/Apps/Software/InstanceSeg/downloaded/fluorescence_nuclei_and_cells-0.1.0";
            assertThat(handler.isPrerequisiteMet("fluorescence_nuclei_and_cells", originalPath)).isTrue();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    // ── getModelStatus ────────────────────────────────────────────────────────

    @Test
    void getModelStatus_notPresentIsMissing(@TempDir Path tmpHome) {
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            assertThat(handler.getModelStatus("fluorescence_nuclei_and_cells", null))
                .isEqualTo(QTraceExtensionHandler.ModelStatus.MISSING);
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void getModelStatus_exactVersionOnDiskIsPresent(@TempDir Path tmpHome) throws Exception {
        File modelDir = tmpHome.resolve("QuPath/v0.7/instanseg/downloaded/fluorescence_nuclei_and_cells-0.1.0").toFile();
        modelDir.mkdirs();
        new File(modelDir, "rdf.yaml").createNewFile();
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            String originalPath = "C:/Apps/Software/InstanceSeg/downloaded/fluorescence_nuclei_and_cells-0.1.0";
            assertThat(handler.getModelStatus("fluorescence_nuclei_and_cells", originalPath))
                .isEqualTo(QTraceExtensionHandler.ModelStatus.PRESENT);
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void getModelStatus_differentVersionOnDiskIsFlaggedAsMismatch(@TempDir Path tmpHome) throws Exception {
        File modelDir = tmpHome.resolve("QuPath/v0.7/instanseg/downloaded/fluorescence_nuclei_and_cells-0.1.1").toFile();
        modelDir.mkdirs();
        new File(modelDir, "rdf.yaml").createNewFile();
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            String originalPath = "C:/Apps/Software/InstanceSeg/downloaded/fluorescence_nuclei_and_cells-0.1.0";
            assertThat(handler.getModelStatus("fluorescence_nuclei_and_cells", originalPath))
                .isEqualTo(QTraceExtensionHandler.ModelStatus.VERSION_MISMATCH);
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void getModelStatus_neverMatchesTheCapturingMachinesLiteralPath(@TempDir Path tmpHome) throws Exception {
        File captured = tmpHome.resolve("elsewhere/some_model-1.0.0").toFile();
        captured.mkdirs();
        String saved = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmpHome.toString());
            assertThat(handler.getModelStatus("some_model", captured.getAbsolutePath()))
                .isEqualTo(QTraceExtensionHandler.ModelStatus.MISSING);
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    // ── getCompat ─────────────────────────────────────────────────────────────

    @Test
    void getCompat_isPartial() {
        assertThat(handler.getCompat()).isEqualTo(Compat.PARTIAL);
    }

    // ── extractModelBaseNames ─────────────────────────────────────────────────

    @Test
    void extractModelBaseNames_deduplicates() {
        List<String> frags = List.of(
            ".modelPath(\"C:/models/fluorescence_nuclei_and_cells-0.1.0\")",
            ".modelPath(\"C:/models/fluorescence_nuclei_and_cells-0.1.0\")",
            ".modelPath(\"/home/user/cell_detection-2.0.0\")"
        );
        List<String> names = InstanSegHandler.extractModelBaseNames(frags);
        assertThat(names).containsExactly("fluorescence_nuclei_and_cells", "cell_detection");
    }
}
