package io.astraebio.qtrace;

import java.time.Instant;

/**
 * Immutable snapshot of a detected image alignment transform.
 *
 * captureSource discriminates the detection path:
 *   "AffineServer"  — QuPath built-in AffineTransformImageServer detected on the active image
 *   "WarpyTextArea" — matrix read live from the Image Combiner Warpy dialog TextArea
 *   "WarpyFile"     — transform_TARGET_SOURCE.json file written by the Warpy extension
 *
 * matrixLayout describes the 6-element array order:
 *   "java_affine"       — [m00, m10, m01, m11, m02, m12]  (Java AffineTransform order)
 *   "imglib2_rowmajor"  — [m00, m01, m02, m10, m11, m12]  (imglib2 / Warpy row-major order)
 */
public class AlignmentRecord {

    // ── Common fields ────────────────────────────────────────────────────────
    public final Instant  detectedAt;
    public final String   detectedBy;
    public final String   captureSource;
    public final String   transformType;
    public final double[] matrix;
    public final String   matrixLayout;

    // ── Moving (source) image — the image being transformed ──────────────────
    public final String   movingImageHash;
    public final String   movingImageName;
    public final String   movingImageUri;

    // ── Reference (target) image — the image aligned to ─────────────────────
    public final String   referenceImageName;
    public final String   referenceImageUri;

    // ── Warpy-specific (null for AffineServer) ────────────────────────────────
    public final String   warpyFile;       // transform JSON filename
    public final String   warpySourceId;   // source image ID from filename
    public final String   warpyTargetId;   // target image ID from filename

    public AlignmentRecord(Instant detectedAt, String detectedBy,
                           String captureSource, String transformType,
                           double[] matrix, String matrixLayout,
                           String movingImageHash,
                           String movingImageName, String movingImageUri,
                           String referenceImageName, String referenceImageUri,
                           String warpyFile, String warpySourceId, String warpyTargetId) {
        this.detectedAt         = detectedAt;
        this.detectedBy         = detectedBy;
        this.captureSource      = captureSource;
        this.transformType      = transformType;
        this.matrix             = matrix.clone();
        this.matrixLayout       = matrixLayout;
        this.movingImageHash    = movingImageHash;
        this.movingImageName    = movingImageName;
        this.movingImageUri     = movingImageUri;
        this.referenceImageName = referenceImageName;
        this.referenceImageUri  = referenceImageUri;
        this.warpyFile          = warpyFile;
        this.warpySourceId      = warpySourceId;
        this.warpyTargetId      = warpyTargetId;
    }

    // ── Decomposed values (layout-aware) ─────────────────────────────────────
    // java_affine    [m00, m10, m01, m11, m02, m12]
    // imglib2_rowmajor [m00, m01, m02, m10, m11, m12]

    public double scaleX()     { return matrix[0]; }
    public double scaleY()     { return "java_affine".equals(matrixLayout) ? matrix[3] : matrix[4]; }
    public double shearX()     { return "java_affine".equals(matrixLayout) ? matrix[2] : matrix[1]; }
    public double shearY()     { return "java_affine".equals(matrixLayout) ? matrix[1] : matrix[3]; }
    public double translateX() { return "java_affine".equals(matrixLayout) ? matrix[4] : matrix[2]; }
    public double translateY() { return matrix[5]; }
}
