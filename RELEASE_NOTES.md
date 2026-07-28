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
