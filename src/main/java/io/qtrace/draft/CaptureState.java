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

package io.qtrace.draft;

import com.google.gson.JsonObject;
import io.qtrace.AlignmentRecord;
import io.qtrace.CellIntensityRecord;
import io.qtrace.ClassifierRecord;
import io.qtrace.DisplaySettingsRecord;
import io.qtrace.ImportedObjectFileRecord;
import io.qtrace.MeasurementMapRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything ActionLogger needs to pick a capture back up exactly where it was — the
 * payload of a live draft's {@code logger_state} block (see {@link CaptureStateCodec}).
 *
 * Plain data, no QuPath types: built by ActionLogger.snapshotState() on the FX thread,
 * serialized on the autosave writer thread, restored by ActionLogger.restoreState() after
 * a crash. Transient watcher/debounce state is deliberately absent — attach() recreates it.
 */
public class CaptureState {

    public List<JsonObject>          capturedSteps               = new ArrayList<>();
    public Map<UUID, Integer>        annotationStepIndex         = new HashMap<>();
    public Map<String, Integer>      manualAnnotationLatestIndex = new HashMap<>();
    public Set<UUID>                 snapshotAnnotationIds       = new HashSet<>();
    public Set<String>               deletedFragments            = new HashSet<>();

    public List<JsonObject>          manualDetectionCorrections  = new ArrayList<>();
    public List<JsonObject>          pendingRemovedDetections    = new ArrayList<>();
    public List<JsonObject>          pendingAddedDetections      = new ArrayList<>();
    public List<JsonObject>          manualAnnotationCorrections = new ArrayList<>();
    public List<JsonObject>          pendingRemovedAnnotations   = new ArrayList<>();

    public Map<String, ClassifierRecord>         knownClassifiers       = new LinkedHashMap<>();
    public List<JsonObject>                      knownObjectClassifiers = new ArrayList<>();
    public Map<String, ImportedObjectFileRecord> knownImportedFiles     = new LinkedHashMap<>();
    public AlignmentRecord                       currentAlignment       = null;
    public List<MeasurementMapRecord>            measurementMapRecords  = new ArrayList<>();
    public List<DisplaySettingsRecord>           displaySettingsRecords = new ArrayList<>();
    public Map<String, CellIntensityRecord>      cellIntensityRecords   = new LinkedHashMap<>();

    /** Set when the session is a Replay of another .qtrace (see ActionLogger.markReplayStart). */
    public JsonObject                            replayedFrom           = null;

    public int    lastKnownStepCount    = 0;
    public int    preExistingStepCount  = 0;
    public int    manualAnnotationCount = 0;
    public int    importCounter         = 0;
    public String imageHash             = null;
}
