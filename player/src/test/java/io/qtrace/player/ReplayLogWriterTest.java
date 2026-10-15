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

package io.qtrace.player;

import io.qtrace.ReplayStamp;
import io.qtrace.chain.Hashing;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The log names the machine, and the trace it produced can point at it by name and fingerprint. */
class ReplayLogWriterTest {

    private ReplayLogWriter open(Path dir) throws Exception {
        return new ReplayLogWriter("sample-record", "srchash", "0.7.0", "all compatible",
            List.of(), null, null, null, dir);
    }

    @Test
    void headerNamesTheMachine(@TempDir Path dir) throws Exception {
        try (ReplayLogWriter w = open(dir)) {
            String text = Files.readString(w.logPath());
            assertThat(text).contains("\nmachine        : " + ReplayLogWriter.hostName() + "\n");
        }
    }

    @Test
    void stampCarriesTheMachineAndTheHashOfTheLogThroughItsSummary(@TempDir Path dir) throws Exception {
        try (ReplayLogWriter w = open(dir)) {
            ReplayStamp stamp = w.finishUnsigned("someone", "srchash", "0.7.0");
            assertThat(stamp.machine()).isEqualTo(ReplayLogWriter.hostName());
            assertThat(stamp.canonicalPayload()).contains("\"machine\":");
            assertThat(stamp.logHash()).isEqualTo(Hashing.sha256Hex(w.logPath()));
            assertThat(w.summaryHash()).isEqualTo(stamp.logHash());
        }
    }

    @Test
    void linkIntoNamesTheFileAndItsSummaryHash_whichSurvivesWhatIsAppendedAfterwards(@TempDir Path dir) throws Exception {
        try (ReplayLogWriter w = open(dir)) {
            JsonObject before = new JsonObject();
            w.linkInto(before);
            assertThat(before.get("log_file").getAsString()).isEqualTo(w.logPath().getFileName().toString());
            assertThat(before.has("log_sha256")).isFalse();   // not finished: no exact fingerprint yet

            ReplayStamp stamp = w.finishUnsigned("someone", "srchash", "0.7.0");
            JsonObject from = new JsonObject();
            w.linkInto(from);
            String fingerprint = from.get("log_sha256").getAsString();

            w.appendBatchTransition("next image");
            w.appendSignedStamp(stamp);
            assertThat(fingerprint).isNotEqualTo(Hashing.sha256Hex(w.logPath())); // the file moved on…
            assertThat(w.summaryHash()).isEqualTo(fingerprint);                    // …the fingerprint did not
        }
    }
}
