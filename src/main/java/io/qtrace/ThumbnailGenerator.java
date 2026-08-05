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

package io.qtrace;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Renders a small square JPEG thumbnail for the current image, used by the Workspace
 * table and the certificate fiche on qtrace.ca.
 *
 * Takes an already-rendered {@link BufferedImage} — in practice a snapshot of the live
 * QuPath viewer ({@code GuiTools.makeViewerSnapshot}), so the thumbnail shows exactly
 * what the contributor sees: channel colors, brightness/contrast, and any annotation /
 * detection overlays. A raw server-pixel render would miss all of that.
 */
final class ThumbnailGenerator {

    private static final int   TARGET_SIZE = 480;    // px, square
    private static final long  MAX_BYTES   = 500_000; // upload cap requested by product

    private ThumbnailGenerator() {}

    /** Renders and writes {@code <baseName>.thumbnail.jpg} into {@code outputDir}. */
    static Path generate(BufferedImage source, Path outputDir, String baseName) throws IOException {
        byte[] jpeg = renderJpeg(source);
        Path outFile = outputDir.resolve(baseName + ".thumbnail.jpg");
        Files.write(outFile, jpeg);
        return outFile;
    }

    static byte[] renderJpeg(BufferedImage source) throws IOException {
        BufferedImage square  = cropToSquare(source);
        BufferedImage resized = resize(square, TARGET_SIZE);

        byte[] jpeg = encodeJpeg(resized, 0.85f);
        if (jpeg.length > MAX_BYTES) {
            // Comfortable margin at TARGET_SIZE means this should never trigger in
            // practice, but stays defensive rather than silently exceeding the cap.
            jpeg = encodeJpeg(resized, 0.6f);
        }
        return jpeg;
    }

    private static BufferedImage cropToSquare(BufferedImage img) {
        int side = Math.min(img.getWidth(), img.getHeight());
        int x = (img.getWidth()  - side) / 2;
        int y = (img.getHeight() - side) / 2;
        return img.getSubimage(x, y, side, side);
    }

    private static BufferedImage resize(BufferedImage img, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IOException("No JPEG writer available");
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            }
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
