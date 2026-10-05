## What's new in v1.2.5

### Changed — One update message at startup
When updates are waiting, qTrace now shows a single message listing every extension to update — for example "Core 1.2.5 → 1.2.6" — instead of one window per extension. **Install** installs them all in one click, **Later** leaves everything as it is, and **Skip these versions** stays quiet until another version comes out. A single "Update installed" message then offers **Quit QuPath Now**.

### New — Changes to the class list are captured
Adding, renaming, recolouring or removing a class in QuPath's class list is now recorded in the trace, and the Player creates the added classes again before anything else when the trace is replayed.

### New — Extensions add their own button to the panel
When your organization gives you an extension that goes with **Dashboard** or **Version(s)**, it adds a button of its own right after that one — under it on the mini-panel. Like the others, it is a toggle: a second click closes what it opened.

### Changed — Stamp integrity is checked much faster
On projects holding large certificates, the Dashboard, the panel's integrity alert and the extensions that read every image no longer reopen every certificate of the case for each image.

### New — Workflows, for the organizations that enabled the Workflow Editor
A workflow is a sequence of instructions grouped in packets, composed from a record in **⑃ Version(s)** (**Super User**), saved as a `.qtflow` file or published to qtrace.ca. The Player opens a workflow — a local file or a `qtw_…` ID — draws its packets, and plays it whole or packet by packet; playing it records a trace of its own. **Settings › Paths** gains a **Workflows (.qtflow)** folder: `<project>/qTrace/workflow` by default.

### Changed
- In **⑃ Version(s)**, drag the divider to set the width of the detail panel.

### Fixed
- The mini-player's menus were grey on white; they are dark with light entries.
- Leaving an image no longer adds an unstamped session when nothing was done on it, and closing **Brightness & contrast** without changing anything is no longer recorded.

---

## What's new in v1.2.4

### New — A mini-panel on the image
qTrace now opens as a compact column docked on the viewer: the capture indicator (a blinking red dot while recording, grey pause bars otherwise) and the panel's buttons, each showing its name as soon as you hover it. **⤢** opens the full panel, **✕** takes the column off the image and **⠿** moves it. While a push to your workspace runs, Upload is replaced by a spinner. The panel now opens when QuPath starts; untick **Settings › Appearance › Show the panel when QuPath starts** to open it on demand only. The **Version(s)** button has a new icon, a small commit graph.

### New — UX design
A design pass across qTrace: every dialog now shares the look of Settings, panel buttons are toggles (a second click closes what the button opened), **Esc** cancels, **⤢ / ⤡** switch between the mini-panel and the full panel, and **▶ Start** on the mini-panel reopens a recent project. Stamping unsaved work offers **Save now & Continue**.

### New — Take an instruction out of the replay
In **⑃ Version(s)**, every replayable instruction has a **−** button that takes it out of the replay, and **+** puts it back. The instruction stays in the trace — dimmed, labelled "taken out of the replay", with who did it and when — but the Player never runs it: not step by step, not in continuous play, not in batch, and it is left out of the replay scripts. If you touch nothing, every instruction is replayed as before. Anything done since the last stamp can be changed; a stamp freezes the choice. By default the Player does not list these instructions; untick **Settings › Preferences › Hide the instructions taken out of the replay in the Player** to see them greyed out.

### Changed — A stamp covers everything since the last one
A stamp now validates all the work recorded since the previous stamp, not only the session in progress: the stamped session carries the instructions of the earlier sessions, unstamped ones included, even when QuPath had not kept them in its own history. Each certificate therefore replays the whole image on its own. When instructions were taken out of the replay, the stamp dialog says so on a **Replay (since last stamp)** line before you sign.

### Changed — The Version window follows your work
**⑃ Version(s)** now shows the capture **In progress** as a last session, so it opens before the very first stamp, and it updates by itself as you work and when you open another image. Click a session in the graph to list only its steps; click beside the nodes to list them all again. Steps inherited from an earlier session are no longer repeated in the next ones. The graph and the list both run from the latest to the oldest, each session headed by its milestone.

### New — A local Git history of your QuPath project
Each recorded session — autosave, stamp or replay — adds a commit to a local Git history in the project folder, covering the `.qtrace` files and their satellites, scripts and classifiers. Commits are authored with your certified name and your account email.

### Changed — Bug reports describe your workstation
A report of type **Bug** now also carries your workstation's configuration — OS, processor, memory, graphics card, screens, Java, free disk space — and the list of QuPath extensions with their versions; a line in the dialog says so. The same configuration is sent at each QuPath start, along with the qTrace version. Never the machine name, your login or a file path.

### Changed — A record opens only for who may read it
Opening a record in the Player by `qtc_…` ID or by link now requires a right on it: it is yours, its owner shared it with you (portal › **Shares**), or you hold an anonymous share link (`…/share/g/…`, now accepted by the Player). Knowing the ID is no longer enough — the Player then says **You don't have access to this record. Ask its owner to share it with you.** The owner sees who opened the record, and when, on its page of the portal. Local `.qtrace` files open as before, and a certificate's verdict stays public.

### New — Training, for the organizations that enabled it
When your organization gives you the training module, the Player's **📂 Open** menu gains a **Training…** entry: the training steps of your organization, each with its explanation and its reference records, which open in the Player in one click. Tick **I have done this step** to keep track of where you are.

### Fixed
- The Player replays the certified session of a `qtc_…` certificate rather than the image's whole history; **Full image history** replays everything.
- A manual annotation taken out of the replay stays out when qTrace captures it again.
- Opening the panel no longer adds a "Panel opened" line to the activity log.

---

## What's new in v1.2.3

### New — Every step in the Version window
**⑃ Version(s)** now lists, under the commit graph, every action captured on the image, oldest first: one grey dot per step with its time, its command and a line saying what it did — what InstanSeg detected and on which channels, which classifier produced annotations or detections, the stain vectors used. Pixel classifier training and Warpy alignment, which QuPath does not record as steps, appear at their time too. Each session ends on its stamp, a dot in the colour of its confidence, or on a hollow dashed dot when it was never stamped. Click a step to read its full script, click a stamp to open the commit: the graph follows. Dates in the graph are now shown in your local time.

### Changed — No more waiting for the identity check
Signing in from **Getting started** no longer waits for your identity verification: as soon as your account is ready (terms accepted, passphrase chosen), **Authorize QuPath** installs a **provisional identity certificate** and you can work and stamp right away. The panel says **Identity verification pending · provisional until …** in orange until your identity is verified on qtrace.ca; qTrace then swaps in your verified certificate by itself (at startup, then every hour) and tells you **Your identity is verified**. The stamps you made meanwhile are signed with the same key and become certified under your verified name.

### Changed — Power Users are certified from the start
Members of the qTrace Power User Group, whose identity qTrace vouches for, get a verified certificate as soon as they sign in, with no identity check to go through.

---

## What's new in v1.2.2

### Added — Getting started: from nothing to your certified identity, one restart
After **Install**, a wide **Getting started** window walks you through setup step by step (‹ › or the step track at the bottom). It checks that qtrace.ca and GitHub are reachable (and says to ask your IT team if a proxy or firewall blocks them), then asks **Do you have an invitation code?** Enter the code from your email and click **Continue with this code**: your browser opens qtrace.ca to create your account with that address, then the identity verification and your passphrase; at the end, **Authorize QuPath**. QuPath waits for you (up to an hour), receives your identity certificate on its own — no file to handle — installs Compliance and restarts once. **I already have a qtrace.ca account** takes the same path without a code; **Not now** keeps qTrace Core and ends on **qTrace is on board**. Reopen it any time from `Extensions › QTrace › Getting started…`.

### Added — A Welcome after the restart
The first start with your identity certificate opens a **Welcome**: your first steps (record an analysis, stamp a result, replay a workflow, report what is missing), personalised for your organisation when you belong to one, ending on **qTrace is on board**. `Extensions › QTrace › Welcome…` reopens it.

### Changed — Identity certificate and signed record
What used to be called your "license" is your **identity certificate** (Settings page **Certificate**); the `.qtcert` produced by each stamp is a **signed record**. Error messages about a missing or expired certificate now point to **Getting started** instead of the certificate file, which stays available in Settings for a QuPath that cannot reach qtrace.ca.

---

## What's new in v1.2.1

### Added — Your capture is saved continuously, and survives a crash
qTrace now writes your capture to a local draft a few seconds after every change; the panel header shows **Saved · …**, **Saving…** or **⚠ Not saved**. If QuPath crashes, reopening the image offers **Recover unsaved work**: **Restore** brings the image and the capture back exactly as they were just before the crash (a few seconds lost at most), or **Keep as unstamped session, start from the saved image** keeps what was recovered as an unstamped session. A new **Settings › Autosave** section turns this on or off and sets how often the image's work is copied for recovery — **Auto** (small images at every change; large ones after each key step, and at most every 60 s otherwise) or **Custom** — with a live **What this means for you** box saying what a crash could cost and how much disk it takes. The copy is dropped as soon as you save your work in QuPath.

### Added — Unstamped sessions are kept and shown
Switching images, closing one or quitting QuPath without stamping no longer loses the work in between: it's written to the `.qtrace` as an **unstamped session**. The version graph draws it as a dashed hollow circle marked **Unstamped**; in the Dashboard, ✓ Validated and 🛡 refer to the last stamp, with **(+N unstamped)** when later work wasn't stamped, and the Image & Validation card and Session timeline point them out. The "Unstamped image" reminder now says **Continue — keep it as an unstamped session**.

### Added — A minimal replay player, floating over the image
**↻ Replay** now opens a compact player right over the image instead of a separate window: open a replay (a local `.qtrace` file, or a qtrace.ca ID or share link), play and pause, run one instruction at a time, see the replay's compatibility at a glance, and replay it on the open image or on every image of the project. A line under the buttons shows a spinner while the replay loads, "Ready to play" once it's loaded, the instruction running, and a closing summary: instructions applied, failures, `.qtrace` written. Drag the player anywhere over the image by its ⠿ handle and widen it by its borders; its position and width are remembered. **⤢** opens the full Player on the same replay and **⤡** comes back to the compact one — only one player is ever open.

### Added — Each replayed image gets its own .qtrace
When a replay ends on an image, qTrace writes that image's `.qtrace`: exactly the instructions that ran successfully (failed or unchecked ones are left out), plus a `replayed_from` link to the source replay. On a never-opened image, the steps QuPath adds by itself when opening it (e.g. "Set image type") are left out; an image that already had a history keeps it, with the replay appended. The panel's step counter now goes up by one for every instruction that ran successfully.

### Added — Replay only what was validated
In the Player, an instruction recorded only in unstamped sessions is marked **◌** (orange title, tooltip naming the session), and **Validated steps only** unchecks them all at once. The exported script comments them as `// [unstamped] session …`, and `qtrace-replay --validated-only` leaves them out.

### Changed — Replay only moves forward
An instruction applied in QuPath can't be undone, so the Player no longer offers ⏮, ◀ or ⏭, and clicking an instruction no longer moves playback (a failed one still opens its full error). **▶ after a pause now resumes where playback stopped** — it used to start over from the first instruction and apply the earlier ones a second time. With only the compact player open, a failed instruction pauses playback on that step; ▶ carries on with the next one.

### Added — The stamp's certificate is checked from QuPath
The integrity alert now also verifies the stamp's `.qtcert` certificate and its place in the case chain. **Certificate missing or altered** shows in red when the certificate was deleted (even together with its line in `chain.jsonl`), modified, removed from the chain, separated from its parent, or doesn't match its signature; **🔍 Why?** gains a "Certificate & chain" section. Certificates issued from this version on (format 1.1) sign the whole stamped session, not only its key fields.

### Changed — A more compact panel, with a fuller Activity log
The panel now shrinks down to about 360 px wide (it was locked at 760 px with a license): toolbar buttons drop their captions — names stay in the tooltips — then wrap onto a second line if needed. The **Activity log** folds away with a click on its title (the latest line stays visible next to it, and the choice is remembered), every line starts with the time it happened, and it now also shows what was done before the panel was opened. The `Extensions › qTrace` menu says **Settings...** instead of "Preferences...", like the panel's ⚙ button.

### Changed — You save your work, not the image
The image itself — its pixels — is never modified: what QuPath saves, and what a stamp certifies, is the work on top of it (annotations, detections, measurements, history) in the image's QuPath data file (`.qpdata`). The wording now says so: before stamping, "Your work on this image isn't saved yet"; the integrity alert reads "Work on this image changed since the last stamp"; **🔍 Why?** and `qtrace-verify explain` name the section "QuPath data file (.qpdata)".

### Fixed — Scattered "qTrace_…_CaseConflict" folders
With **Use Project Folder** on, a project that already had a `qtrace/` folder got a second `qTrace/` folder next to it on every export. Sync clients such as Synology Drive or OneDrive, and Windows or macOS disks, treat those names as the same and renamed each new one `qTrace_<machine>_<date>_CaseConflict`, scattering `.qtrace` files where the Dashboard and the version graph never looked. qTrace now reuses the existing folder, whatever its case. Folders already scattered are not moved automatically.

---

## What's new in v1.2.0

### Changed — qTrace now installs through a single loader
qTrace is now installed as one small file, `qtrace-loader.jar`, in the QuPath extensions folder. On first start it downloads qTrace Core from this release and, with a license, qTrace Compliance from qtrace.ca, then starts right away. Modules are signed `.qtjar` files kept in `extensions/qtrace/`, which QuPath never locks: updates no longer get stuck on Windows, and only modules signed by qTrace are ever loaded.

**Upgrading from 1.1.x (one time):** quit QuPath, delete `qtrace-core-….jar` and `qtrace-compliance-….jar` from the extensions folder (keep your `.qtlicense`), put `qtrace-loader.jar` there instead and start QuPath. Your license and settings are kept.

### Added — Integrity alert in the panel and the Dashboard
qTrace now checks the open image's latest stamp whenever you open or switch images, open the panel, or stamp. A line under the image name warns in red when the stamp itself was tampered with (**Stamp corrupted** — a signed field was edited; **.qtrace edited** — a step, parameter or other field no longer matches the certificate) and in orange when the evidence around it changed (**Image data changed since the last stamp**; **Satellite files changed** — thumbnail, GeoJSON or validation log). The Dashboard's Image & Validation card shows the same status on an **Integrity** line. Nothing is shown when everything is intact.

### Added — "🔍 Why?": see exactly what differs from the stamp
Next to every alert, **Why?** opens a window comparing the current state with the stamp's certificate: the edited `.qtrace` fields (only the changed passage of long scripts), modified or missing satellite files, and — read from the image data on disk — annotations removed, added, recreated, moved or reclassified, plus detection counts per class. The same diagnosis is available offline with `qtrace-verify explain`.

### Added — Detections, annotations and GeoJSON fingerprints in each stamp
Each stamp now records the number of detections per class and an order-independent fingerprint of detections and annotations, plus the hash of the annotations GeoJSON, so a later change to them can be pinpointed.

### Fixed — Stamping an image with unsaved changes
The stamp certifies the image data as saved on disk; stamping unsaved work could certify a file that did not contain what was validated. Stamp now asks you to save the image first (File › Save, Ctrl+S).

## What's new in v1.1.5

### Added — Brightness & contrast capture, replayed as a real step in the Player
QuPath never records anything in the workflow history for the Brightness & contrast dialog, so a replayed analysis could reproduce every detection and measurement yet look completely different on screen. qTrace now watches that dialog and snapshots the live per-channel min/max, color, visibility, gamma, grayscale and invert-background state when it closes. Each snapshot becomes a real, executable **Display settings** step in the Replay Player's Instructions list (checkbox, status, timer — same as any other step) and in the exported MetaScript, so a figure's exact visual appearance is reproducible, not just the underlying pixel data.

### Fixed — InstanSeg replay silently substituting a mismatched model version
A locally-installed but differently-versioned InstanSeg model (e.g. 0.1.1 when the capture used 0.1.0) was silently treated as satisfying the pre-flight check, and the replay ran with the wrong weights with no warning — producing results that diverge from the original run. Exact-version match is now tried first; a base-name fallback still runs at replay time but logs an explicit WARNING, and pre-flight no longer reports a differently-versioned model as present.

### Added — Select all button in the Player's Target image(s) panel
Checking every project image one-by-one before a batch replay was tedious — a single button now marks every target as checked in one click.

## What's new in v1.1.4

### Changed — Settings redesigned as a two-pane sidebar
Settings now uses a Thunderbird-style layout: a left-hand menu (Identity, Licence, Paths, Preferences, Appearance, About qTrace) next to a scrollable content pane, in a wider/shorter window. Security's two settings moved into Preferences under a "SECURITY" sub-heading rather than sitting in their own tab, and their hint text is now a tooltip on hover instead of a permanently-visible line. About qTrace is now reachable from inside Settings, reusing the same content as the standalone About dialog.

### Added — Digital Identity card (Compliance)
The Identity page now shows a read-only Digital Identity card for validators with a Compliance license: the ED25519 signing key, the Polygon anchor transaction (linked to the block explorer) and date, and the public badge status. A "Manage my credentials →" link opens the portal for professional registry / diploma / ORCID management, which stays a portal-only feature.

### Changed — Post-update dialog offers to restart now
After an auto-update finishes installing, the dialog now offers **Restart Now** / **Later** instead of just an OK — restarting immediately closes QuPath so the new JAR takes over on the next launch.

### Fixed — Cloud Workspace push failure showed a raw "ERROR:" prefix
A push failure (e.g. missing/unreadable license) was logged to the panel with the internal "ERROR:" marker still attached instead of a clean message.

---

## What's new in v1.1.3

### Added — Dashboard: Export to CSV
A new **Export** button sits next to Import in the side panel. It opens a field-selection dialog (everything checked by default — every Dashboard column, `image.name`/`image.channels`/`image.type`, and a per-class annotation breakdown) and writes one CSV row per `.qtrace` file, covering every image found, not just what's currently filtered in the Dashboard. Annotation classes get their own dynamic columns, one per class name found across the exported set. UTF-8 BOM included for correct accented-character display in Excel.

---

## What's new in v1.1.2

### Added — Import objects from file, captured properly
`File > Import objects from file` used to fall through the generic annotation listener and get mislabeled as individual "Manual annotation" entries — no record of the source file, and imported detections weren't captured at all. It's now its own step ("Import objects from file: <name>"), recorded in the `.qtrace` with the file's sha256 and object count. A companion copy of the imported file is written next to the export and resolved by name only when the Replay Player replays that step — no dependency on the original file's path. On Compliance, the companion file is pushed to the cloud Workspace bucket alongside classifiers and the thumbnail, and shows up as a downloadable link on the certificate page.

### Added — Dashboard: Loaded Extensions & External Files cards
- **Loaded Extensions** shows the name/version of every QuPath extension active on the machine that produced a session's export — captured since early on, never surfaced anywhere until now.
- **External Files** lists companion artifacts for the selected session (thumbnail, `.qtcert`, master CSV, imported object files, annotations GeoJSON). Backed by a new `external_files[]` manifest written incrementally as each artifact is generated; sessions exported before this manifest existed still show their thumbnail via an on-disk fallback.

---

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

![Replay Player — step-by-step replay with per-step status, Target image(s) batch selection, and Activity log](https://raw.githubusercontent.com/RomainTourte/qTrace-core/main/docs/screenshots/v1.1.0-replay-player.png)

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
