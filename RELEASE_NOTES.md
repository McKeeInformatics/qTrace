## What's new in v1.1.1

### Changed — Replay Player polish
- Default window height now matches the Dashboard's (was noticeably shorter)
- Pre-check now lands above the Target image(s) block instead of below it, so the config check is seen before picking target images
- Target image(s) block now opens to a usable size (~4 rows) instead of a sliver of 1-2 rows, while staying freely resizable
- "Target image(s) — none open" reworded to "Target image(s) — Select at least one image"
- The CONSOLE header is now simply "Activity log"
- Step durations under 5 seconds now show in milliseconds (e.g. "3120ms") instead of rounding down to whole seconds under MM:SS, which hid real differences between similarly-fast steps

### Changed — License badge
The panel's certified-license badge drops the validator key fingerprint next to the name ("Certified for NAME — KEY · until DATE" becomes "Certified for NAME · until DATE"). Hovering the name now shows the license holder's platform account email in a tooltip.

---

## What's new in v1.1.0

### Added — Replay Player

![Replay Player — step-by-step replay with per-step status, Target image(s) batch selection, and Activity log](../docs/screenshots/v1.1.0-replay-player.png)

qTrace could already regenerate a Groovy script from a `.qtrace` file — but you had to open it yourself in the Script Editor and click Run, with no way to know whether one step actually worked before the next one ran. The new **Player** executes the replay directly, step-by-step or continuously, with a real per-step status.

Starting from an existing `.qtrace`, the Player lets you:

- **Replay step-by-step or continuously** — advance one instruction at a time (◀ ▶│) to inspect each effect on the image, or hit ▶ Play and let the whole pipeline run through, with each step's status (OK / failed / skipped) updated live
- **Choose which instructions to replay** — every step in the trace has its own checkbox; you can uncheck whatever isn't relevant (an export, a step specific to the original environment) without touching the source file
- **Automatically replay across multiple images in the project (Target image(s))** — check one or more images in the current project and hit Play: the Player opens each image in turn and replays the checked instructions against it, with no manual step in between. Useful for verifying that a pipeline behaves reproducibly across a whole batch of images, not just the one it was recorded on
- **Check compatibility before running** — a pre-check panel automatically verifies file integrity, required extensions, referenced ML models, and the QuPath version, and warns if anything's missing before replaying
- **Keep a record of every run** — each run produces a timestamped log (one per image, in a batch), with per-step detail and a final summary; a **Stamp** button lets you sign a run's result as proof of execution
- **Export the replayed code** — the Export Code button assembles a standalone Groovy script from the checked instructions, reusable outside the Player (Script Editor, sharing)

### Fixed — Apparent freeze during a long segmentation
A heavy step (segmentation, cell detection) could block QuPath for several minutes to the point where the OS would show "Not Responding," even though the computation was genuinely progressing in the background. The Player now runs each step on a dedicated thread instead of the UI thread — matching what QuPath itself already does by default when running a script from the Script Editor.

### Added — Reset button
New button in the main panel to reset the current capture and start tracking from this point forward, without needing to close/reopen the image.

---

## What's new in v1.0.16

### Added — Annotated thumbnail on export and cloud Workspace push
Exporting now also renders a small square JPEG thumbnail alongside the `.qtrace` file — a snapshot of the current QuPath viewer (channel colors, brightness/contrast, and any annotation/detection overlays), not raw server pixels, so it actually shows what the contributor was looking at rather than a dark, uncomposited render. Compliance's cloud Workspace push uploads it together with `.qtcert`/`chain.jsonl`/classifiers; the Workspace table and the certificate fiche on qtrace.ca now show this thumbnail instead of text-only rows.

---

## What's new in v1.0.15

### Added — Unstamped image reminder on image switch
Users routinely forget to Stamp before switching to another image — right when QuPath's own "save changes" prompt appears. qTrace now checks, on every image change, whether the image being left has captured actions with no matching stamp (tracked by image hash plus step count, since new steps can be added to an image after it was already stamped once). If so, a reminder dialog offers to Stamp now, continue without stamping, or stop asking for the rest of the session.

### Added — Name filter in Confirm training images dialog
The confirmation dialog for multi-image pixel classifier training sets can list every image in the project. A filter field now sits above the list — typing narrows the checkboxes down by image name (the active image stays pinned and visible regardless of the filter).

### Fixed — Dashboard Annotations column truncated regardless of width
The per-class annotation breakdown shown in the Dashboard table's Annotations column was hard-truncated to 24 characters before display, so widening the column never revealed more text. The column now shows as much as its width allows, like the other columns (full breakdown remains available via tooltip).

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
