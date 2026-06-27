## What's new in v1.0.8

### Detection correction audit
When an analyst manually deletes or subdivides detections — removing false positives, separating merged cells, correcting AI over-segmentation — qTrace now **captures the event and prompts for a justification note**.

A non-intrusive dialog appears automatically:
- Heading shows the count: *"3 detections manually deleted"*
- Free-text field for a short rationale: *"merged artifact — two clearly distinct nuclei"*
- **Save** records the note · **Skip** records the event silently

All corrections are exported to the `.qtrace` sidecar under `manual_detection_corrections[]`, with timestamp, author, deleted object UUIDs, centroid coordinates, and the justification note. Split operations (delete + re-draw) are recorded as type `"split"` with both `deleted[]` and `created[]` arrays.

**Silent mode:** disable the prompt entirely via Settings → Capture → "Prompt for detection correction note". Events are still recorded — just without asking for a note each time.

### Fix — multi-detection deletion in QuPath 0.7
QuPath 0.7 fires `OTHER_STRUCTURE_CHANGE` (not `REMOVED`) when the user deletes ≥ 3 objects via the confirmation dialog — with an empty `changed` list. This was a blind spot in qTrace's hierarchy listener.

The fix uses a `PathObjectSelectionListener` to snapshot the selected detections before the dialog, then identifies removed objects by diffing against the hierarchy after the `OTHER_STRUCTURE_CHANGE` event. Deletions of 1–2 objects (no dialog) continue to use the direct `REMOVED` path.

---

### Added
- **Detection correction audit** — when the user manually deletes or splits detections, qTrace captures the event and optionally prompts for a justification note ("merged artifact", "two clearly distinct nuclei", etc.)
  - Dialog shows count ("3 detections manually deleted") with an optional free-text note field
  - Silent mode available: disable the prompt via Settings → Capture → "Prompt for detection correction note"
  - Corrections exported to `.qtrace` sidecar under `manual_detection_corrections[]` with timestamp, author, deleted UUIDs, centroid coordinates, and note
  - Split detection (delete + re-draw) recorded as type `"split"` with both `deleted[]` and `created[]` arrays

### Fixed
- **QuPath 0.7 multi-deletion event** — QuPath 0.7 fires `OTHER_STRUCTURE_CHANGE` with an empty `changed` list (instead of `REMOVED`) when the user deletes ≥ 3 objects via the confirmation dialog; qTrace now tracks the selection via `PathObjectSelectionListener` and identifies removed detections by diffing against the hierarchy after the event

---

## Installation

Drop `qtrace-core-1.0.8.jar` into your QuPath extensions folder:

| Platform | Path |
|---|---|
| macOS | `~/Library/Application Support/QuPath/v0.7/extensions/` |
| Windows | `%APPDATA%\QuPath\v0.7\extensions\` |
| Linux | `~/.local/share/QuPath/v0.7/extensions/` |

Requires **QuPath 0.5+** (tested on 0.7.x).
