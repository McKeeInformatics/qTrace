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

import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of QuPath's built-in "Brightness & contrast" dialog
 * (View > Brightness/contrast — {@code qupath.lib.display.ImageDisplay}) at the moment
 * it was closed.
 *
 * Unlike {@link MeasurementMapRecord}, this one <b>is</b> replayable: every field maps onto
 * a public {@code ImageDisplay}/{@code QuPathViewer} scripting call
 * ({@code setMinMaxDisplay}, {@code setChannelSelected}, {@code setLUTColor}, {@code setGamma}),
 * so {@code QTraceReplayEngine} turns each captured record into a real, executable step — this
 * is what makes the final figure's appearance reproducible, not just the underlying pixel data.
 *
 * Created by ActionLogger.snapshotDisplaySettingsState(); consumed by QTraceExporter.
 */
public class DisplaySettingsRecord {

    /** One channel row as shown in the Brightness & contrast table. */
    public record ChannelSetting(String name, Integer colorRgb, float minDisplay, float maxDisplay, boolean selected) {}

    public final List<ChannelSetting> channels;
    public final double  gamma;
    public final boolean grayscale;
    public final boolean invertBackground;
    public final Instant timestamp;

    public DisplaySettingsRecord(List<ChannelSetting> channels, double gamma,
                                  boolean grayscale, boolean invertBackground, Instant timestamp) {
        this.channels         = channels;
        this.gamma            = gamma;
        this.grayscale        = grayscale;
        this.invertBackground = invertBackground;
        this.timestamp        = timestamp;
    }

    /** True when {@code other} shows the image the same way — whenever each was read. */
    public boolean sameSettings(DisplaySettingsRecord other) {
        return other != null && gamma == other.gamma && grayscale == other.grayscale
            && invertBackground == other.invertBackground && channels.equals(other.channels);
    }
}
