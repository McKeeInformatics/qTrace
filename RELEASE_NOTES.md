## What's new in v1.0.15

### Added — Confirm multi-image pixel classifier training sets
QuPath's "Pixel classifier training images" dialog lets a classifier train on annotations from several project images, but exposes that selection nowhere in its public API — it lives in a private field of a transient, internal UI class with no stable hook. qTrace previously only ever recorded the active image's annotations, silently missing every other image that contributed to training.

qTrace now applies a strict compliance rule: never guess silently. It records "current image only" without asking *only* when that's actually certain — a single-image project, or no other project image holding any annotation at all. The moment another image has **any** annotation, a confirmation dialog opens: the active image is locked and checked, images whose annotations match the classifier's classes are pre-checked as a suggestion, and everything else stays visible and toggleable — a matching class never substitutes for explicit human confirmation. The confirmed list is stamped as `training_images` in the `.qtrace`/TPC JSON and shown in the Dashboard's Pixel Classifier card.

### Added — Dashboard loading overlay
Opening the Dashboard on a project with several/large `.qtrace` files left the table and detail cards visibly empty for a moment while the background scan ran — easy to mistake for a broken or frozen window. A semi-transparent overlay with a spinner and "Loading data…" now covers the table/detail area for the duration of the scan.

### Fixed — Upload availability recheck
The Upload button could stay disabled after opening an image whose SHA-256 hash was still being computed in the background, since nothing re-triggered the check once the hash landed. It now re-checks automatically as soon as the hash is ready.

### Fixed — Icon button caption contrast
Caption text under Dashboard/Import/Upload/Replay/Report/Versions used a near-invisible color against the panel background. Switched to a readable muted tone.

### Changed — Gold badge for certified validator in Batch Export
The Batch Export dialog now shows the same certified-identity badge (checkmark, name, institution, expiry) as the single-image stamp dialog, instead of a plain locked text field.

---

## What's new in v1.0.14

### Redesigned — Panel toolbar
Icon-only glyphs with hover-only tooltips left users guessing which button did what. The toolbar now shows labeled, grouped vector icons: **Stamp** (was "Record" — capture itself is passive, this validates & stamps), **Upload**/**Replay**/**Version(s)**/**Report** (Workspace & Analysis, Compliance only), **Dashboard**/**Import** (always available). A **Recording**/**Paused** status (top-right, next to the title) replaces the old ambiguous record button semantics, and a certified-license badge under the title shows "Certified for {name} — {id} · until {date}" in gold when a Compliance license is active, or "Core edition" in gray otherwise.

### Added — Manual annotation correction tracking
Manual annotation deletions now prompt for a justification note and are recorded in the `.qtrace`, mirroring the existing detection-correction audit trail (same dialog, same Settings toggle). Audit-only — not replayable.

### Added — Validator identity locked to certified license
The validator name field in Settings and the Batch Export dialog now locks to the license holder's certified name (read-only, with an explanatory tooltip) whenever a valid Compliance license is active, so a stamp can no longer be signed under someone else's identity.

### Added — Upload auto-enables for already-stamped images
The Upload button previously only enabled right after a fresh stamp in the current session. It now scans `case_<id>/certs/*.qtcert` under the export directory on image change, matching by image hash, so a previously-stamped image is upload-ready again without re-stamping.

### Changed — Smaller `.qtrace` exports
Per-vertex point/polygon coordinate arrays are no longer embedded in the `.qtrace` JSON — they were redundant with the accompanying `geojson_file` and unused by both compliance signing (which only covers `qpdata_sha256`) and replay (which re-runs the step rather than redrawing stored points).

---

## What's new in v1.0.13

### Added — Dashboard "Add Metadata" button
A new **Add Metadata** button sits next to "Open .qtrace" in the Image & Validation card. It opens a small dialog to set a project image metadata key/value pair directly from the Dashboard — the key field suggests keys already in use across the project (plus "Training"/"Test" as defaults), but you can also type a new one.

---

## What's new in v1.0.12

### Added — Dashboard "Annotations" column
The Dashboard table now shows a per-image **Annotations** column with the total annotation count from the latest session, sortable like the other columns.

---

## What's new in v1.0.11

### Added — Dashboard "Extensions" card
Runs of extension models (InstanSeg, and any other QuPath extension using the same fluent `.builder()` pattern) now get their own card in the Dashboard, showing every input parameter that was actually used — model, device, threads, tile size/padding, output type, measurement/color flags, and the full list of input channels (no longer truncated, however many channels were selected). Identical consecutive re-runs are collapsed with a `×N` badge instead of listed separately.

Parameter labels, order, and formatting are driven by a small declarative schema (`io/qtrace/extensions/extension-params.json`), so a new extension can be added by declaring its fields in JSON — no Java changes required. Any parameter not covered by a schema (or from an undeclared extension) still displays, via a generic fallback, so nothing is ever silently dropped.

### Added — "Open" button for .qtrace files
Both the main panel (next to the image name) and the Dashboard (next to each image's detail card) now have an **Open** button that launches the `.qtrace` file directly via the OS's file-open command (`xdg-open` / `open` / `cmd start`), instead of only being readable by digging into the export folder.

### Added — Dashboard auto-selects the current image
Opening the Dashboard while an image is open in QuPath now automatically selects and displays that image's row and detail cards — no more hunting for it in the table.

### Fixed — Dashboard freeze / "not responding" on large .qtrace files
`.qtrace` files (several MB for long multi-session workflows) were read and JSON-parsed synchronously on the JavaFX thread on every Dashboard open/refresh, freezing the whole QuPath window for 10+ seconds and triggering the OS "not responding" watchdog. File I/O and JSON parsing now run on a background thread; only the lightweight UI construction happens on the FX thread afterward.

### Fixed — InstanSeg runs invisible in the Segmentation card
`SEG_KEYWORDS` had a typo (`"instantseg"` instead of `"instanseg"`), so no InstanSeg step ever matched the keyword filter — every InstanSeg run was silently absent from the Segmentation card since it was introduced. Corrected.

---

### Fix — auto-update never actually converged
`QTraceCompliancePlugin.COMPLIANCE_VERSION` (and `QTraceController.VERSION`) were hardcoded string literals, last updated for v1.0.8 and never touched again across the 1.0.9/1.0.10 bumps. Even with a single, correctly-named JAR on disk and no stale duplicate, the loaded class kept reporting `"1.0.8"` forever — so the update dialog re-offered the same "upgrade" after every restart no matter what was actually installed. Both constants now read the version straight from the JAR manifest instead of a literal that can silently go stale.

---

### Fixed
- **Version constants now read from the JAR manifest** (`Implementation-Version`) instead of a hardcoded literal, so the auto-updater's version comparison can never drift out of sync with an actual release again

---

## Installation

Drop `qtrace-core-1.0.12.jar` into your QuPath extensions folder:

| Platform | Path |
|---|---|
| macOS | `~/Library/Application Support/QuPath/v0.7/extensions/` |
| Windows | `%APPDATA%\QuPath\v0.7\extensions\` |
| Linux | `~/.local/share/QuPath/v0.7/extensions/` |

Requires **QuPath 0.5+** (tested on 0.7.x).
