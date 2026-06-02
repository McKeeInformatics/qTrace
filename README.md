# qTrace Core

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20448014.svg)](https://doi.org/10.5281/zenodo.20448014)

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

1. Download `qtrace-core-X.Y.Z.jar` from [Releases](../../releases)
2. Copy to your QuPath extensions folder: `~/QuPath/v0.7/extensions/`
3. Restart QuPath → Extensions > QTrace > Open QTrace Panel

## Enterprise features

Cryptographic certification (Ed25519 + OpenTimestamps), contributor identity
verification, and blockchain anchoring require **qTrace Enterprise** —
contact [mckeeinformatics.com](https://mckeeinformatics.com).

## License

Apache License 2.0 — see [LICENSE](LICENSE)

## Build from source

```bash
./gradlew jar
# Output: build/libs/qtrace-core-X.Y.Z.jar
```

Requires Java 21. Compiled against QuPath 0.5.1 API, runtime target QuPath 0.7.x.
