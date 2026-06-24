## What's new in v1.0.1

### QuPath toolbar integration
A **qTrace MenuButton** is now embedded directly in the QuPath toolbar — no need to go through the Extensions menu.
- Logo icon: **grey** when no image is open, **red** as soon as an image is loaded and recording is active
- Dropdown: **Panel · Dashboard · Preferences · About**

### Autonomous recording
The ActionLogger now starts **as soon as the extension loads**, independently of the floating panel.  
Opening an image in QuPath immediately begins provenance capture. When the panel is opened afterwards, it synchronises to the live logger state — step count, recording indicator, and ◉ button are all pre-filled.

### Single-action "Record your Trace" button (◉)
The four-button row (Generate / Validate / Export / Replay) has been replaced by a single **◉** button in the panel header.  
One click: opens the Expert Validation dialog → on confirm, exports the `.qtrace` sidecar automatically.  
The button is grey (disabled) until at least one step is captured, then turns red.

### MetaScript & GitBridge removed from Core
Groovy replay-script generation and Git commit logic have been extracted from Core.  
Core now focuses exclusively on provenance capture and `.qtrace` export.  
The replay hook is reserved for the Compliance module via `QTracePlugin.replay()`.

### UI refinements
- Panel header subtitle: **"Workflow Provenance — v1.0.1"** displayed below the main title
- Settings dialog: "Meta-Scripts (.groovy)" path row removed

---

## Earlier v1.0.1 changes

### Workflow status in ValidationStamper
Validation dialog now shows the current workflow status (`0 — Not started`, `1 — In Progress`, `2 — Complete`) and lets the validator update it at stamp time.  
Dashboard gains a **Status** column and a status filter.

### Delete .qtrace from Dashboard
Dashboard rows now have a **Delete** button with a confirmation step.

### Bug fix — batch export crash on Windows
`InvalidPathException: Illegal char <\n>` when launching batch export after using the pixel classifier dialog.  
**Root cause:** regex `[^\"]` matched newline characters, passing multi-line strings to `Path.resolve()`.  
**Fix:** changed to `[^\"\r\n]`.

### GeoJSON export path
Training GeoJSON annotations are now written to `trainingDir` (configurable in Settings) instead of `exportDir`.

---

## Installation

Drop `qtrace-core-1.0.1.jar` into your QuPath extensions folder:

| Platform | Path |
|---|---|
| macOS | `~/Library/Application Support/QuPath/v0.7/extensions/` |
| Windows | `%APPDATA%\QuPath\v0.7\extensions\` |
| Linux | `~/.local/share/QuPath/v0.7/extensions/` |

Requires **QuPath 0.5+** (tested on 0.7.x).
