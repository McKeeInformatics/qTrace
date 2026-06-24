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

package io.astraebio.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mutable provenance record for one pixel classifier.
 * Created by ActionLogger.onClassifierSaved(); consumed by QTraceExporter.
 */
public class ClassifierRecord {

    // ── Identity ─────────────────────────────────────────────────────────────
    public final String  name;
    public final String  jsonContent;       // raw .json file content
    public final String  sha256;            // file SHA-256 at save time
    public       String  gitHash;           // set after Git commit; null until then
    public final Instant savedAt;

    // ── Attribution ───────────────────────────────────────────────────────────
    public final String  savedByUser;       // System.getProperty("user.name")
    public final String  imageHashAtSave;   // logger.getImageHash() at save time

    // ── Classifier parameters ─────────────────────────────────────────────────
    public final String    classifierType;      // e.g. "ANN_MLP"
    public final String    outputType;          // e.g. "Classification", "PROBABILITY"
    public final double    resolutionUm;        // pixel size (µm) used during training
    public final JsonArray features;            // feature type names (e.g. ["Mean","Std dev"])
    public final JsonArray channels;            // input channels used
    public final JsonArray scales;              // multiscale values (e.g. [1.0])
    public final JsonArray classes;             // trained class names
    public final String     localNormType;      // e.g. "LOCAL_MIN_MAX"; null if none
    public final double     localNormScale;     // local normalization radius; 0 if none
    public final JsonObject advancedParams;     // Advanced Options: threads, samples, seed,
                                                // feature norm/reduction, boundaries, etc.

    // ── Training data ─────────────────────────────────────────────────────────
    public final List<UUID> trainingAnnotationIds;   // annotation UUIDs at save time
    public final String     trainingAnnotationHash;  // stable hash of the training set
    public       String     trainingGeojsonFile;     // relative path; null until exported
    public       String     tpcFilePath;             // TPC-*.json filename; null until written

    // ── Application (filled when classifier is applied to an image) ──────────
    public int appliedAtStepOrder = -1;
    public int objectsCreated     = -1;

    // ── Fidelity (mutable — updated by integrity checker) ────────────────────
    public volatile boolean modifiedAfterTraining = false;

    public ClassifierRecord(String name, String jsonContent, String sha256,
                            Instant savedAt, String savedByUser, String imageHashAtSave,
                            String classifierType, String outputType, double resolutionUm,
                            JsonArray features, JsonArray channels, JsonArray scales,
                            JsonArray classes, String localNormType, double localNormScale,
                            JsonObject advancedParams,
                            List<UUID> trainingAnnotationIds,
                            String trainingAnnotationHash) {
        this.name                   = name;
        this.jsonContent            = jsonContent;
        this.sha256                 = sha256;
        this.savedAt                = savedAt;
        this.savedByUser            = savedByUser;
        this.imageHashAtSave        = imageHashAtSave;
        this.classifierType         = classifierType;
        this.outputType             = outputType;
        this.resolutionUm           = resolutionUm;
        this.features               = features;
        this.channels               = channels;
        this.scales                 = scales;
        this.classes                = classes;
        this.localNormType          = localNormType;
        this.localNormScale         = localNormScale;
        this.advancedParams         = advancedParams;
        this.trainingAnnotationIds  = trainingAnnotationIds;
        this.trainingAnnotationHash = trainingAnnotationHash;
    }

    /** Worst-case fidelity for this single classifier. */
    public ClassifierFidelity fidelity() {
        return modifiedAfterTraining ? ClassifierFidelity.DEGRADED : ClassifierFidelity.HIGH;
    }
}
