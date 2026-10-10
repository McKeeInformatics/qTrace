# qTrace Core

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20837590.svg)](https://doi.org/10.5281/zenodo.20837590)

Open-source QuPath extension for real-time workflow provenance capture.

## What it does

qTrace Core automatically records every analysis action performed in QuPath:
- Workflow steps (thresholds, classifiers, detections)
- Pixel and object classifier provenance (name, SHA-256, training annotations)
- Image alignment and registration (Warpy, AffineTransform)
- Manual annotation history with per-author attribution
- Cell intensity classification events

Outputs:
- **`.qtrace`** — structured JSON sidecar (one per image, append-only sessions)
- **Groovy Meta-Script** — fully replayable script from raw image to final state
- **Git versioning** — each script committed automatically (no external Git required)
- **`master_validation_log.csv`** — cohort-level export summary

## Installation

With QuPath 0.6 or later:

1. In QuPath: **Extensions › Manage extensions › Manage extension catalogs**, add
   `https://github.com/RomainTourte/qTrace-core` (once).
2. Install **qTrace** from the list, then restart QuPath.
3. Follow **Getting started**; qTrace then lives under Extensions › QTrace.

Without the catalogue: download `qtrace-loader.jar` from [Releases](../../releases/latest) and
drag it onto the QuPath window (or copy it to your extensions folder), then click **Install**.
The loader downloads the signed qTrace modules itself — do not copy `qtrace-core-*` files by hand.

## Compliance features

Cryptographic certification (Ed25519 + OpenTimestamps), contributor identity
verification, and blockchain anchoring require **qTrace Compliance** —
visit [qtrace.ca](https://qtrace.ca) or contact [Romain Tourte](mailto:tourteromain@gmail.com)

## License

**GPL v3** — free for all use. See [LICENSE](LICENSE).

## Build from source

```bash
./gradlew qtjar
# Output: build/libs/qtrace-core-X.Y.Z.qtjar (signed, needs the signing key)
```

Requires Java 21. Compiled against QuPath 0.5.1 API, runtime target QuPath 0.7.x.
