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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the player is given to open, and how it asks qtrace.ca for it. */
class RecordSourceTest {

    private static final String BASE = "https://www.qtrace.ca";

    @Test
    void bareIdIsACertificate() {
        RecordSource s = RecordSource.parse("qtc_01ABC");
        assertThat(s.certId()).isEqualTo("qtc_01ABC");
        assertThat(s.token()).isNull();
        assertThat(s.local()).isFalse();
    }

    @Test
    void aBasicRecordIdIsARecordToo() {
        // qtb_<ULID>: a record of a basic account (no certificate) — opened from qtrace.ca like a qtc_ one
        RecordSource s = RecordSource.parse("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV");
        assertThat(s.certId()).isEqualTo("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV");
        assertThat(s.local()).isFalse();
        assertThat(s.token()).isNull();
        assertThat(RecordSource.parse("https://www.qtrace.ca/verify/qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV").certId())
            .isEqualTo("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV");
        assertThat(RecordSource.contentUrl(BASE, "qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV", "qtrace", null))
            .isEqualTo("https://www.qtrace.ca/api/verify/qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV/qtrace");
    }

    @Test
    void verifyLinkGivesItsId() {
        assertThat(RecordSource.parse("https://www.qtrace.ca/verify/qtc_01ABC").certId()).isEqualTo("qtc_01ABC");
        assertThat(RecordSource.parse("https://qtrace.ca/verify/qtc_01ABC/").certId()).isEqualTo("qtc_01ABC");
        assertThat(RecordSource.parse("https://www.qtrace.ca/verify/qtc_01ABC?x=1").certId()).isEqualTo("qtc_01ABC");
    }

    @Test
    void shareLinkGivesItsTokenAndNoIdYet() {
        RecordSource s = RecordSource.parse("https://www.qtrace.ca/share/g/AbC-12_xY");
        assertThat(s.token()).isEqualTo("AbC-12_xY");
        assertThat(s.certId()).isNull();
        assertThat(s.local()).isFalse();
        assertThat(RecordSource.parse("https://www.qtrace.ca/share/g/AbC-12_xY/?utm=1").token()).isEqualTo("AbC-12_xY");
    }

    @Test
    void anythingElseIsALocalFile() {
        RecordSource s = RecordSource.parse("/home/me/project/image.qtrace");
        assertThat(s.local()).isTrue();
        assertThat(s.certId()).isNull();
        assertThat(RecordSource.parse("C:\\data\\verify\\image.qtrace").local()).isTrue();
    }

    @Test
    void contentUrlCarriesTheTokenOnlyWhenThereIsOne() {
        assertThat(RecordSource.contentUrl(BASE, "qtc_1", "qtrace", null))
            .isEqualTo("https://www.qtrace.ca/api/verify/qtc_1/qtrace");
        assertThat(RecordSource.contentUrl(BASE, "qtc_1", "classifiers", "a b"))
            .isEqualTo("https://www.qtrace.ca/api/verify/qtc_1/classifiers?t=a+b");
    }

    @Test
    void licenceGoesToQtraceOnlyNeverToStorage() {
        assertThat(RecordSource.sendsLicence(BASE, "https://www.qtrace.ca/api/verify/qtc_1/qtrace")).isTrue();
        assertThat(RecordSource.sendsLicence(BASE, "https://abc.supabase.co/storage/v1/object/sign/x?token=y")).isFalse();
        assertThat(RecordSource.sendsLicence(BASE, "https://www.qtrace.ca.evil.example/api")).isFalse();
    }

    @Test
    void refusalsAreExplained() {
        assertThat(RecordSource.refusal(403)).contains("don't have access");
        assertThat(RecordSource.refusal(401)).contains("Sign in");
        assertThat(RecordSource.refusal(404)).contains("not found");
        assertThat(RecordSource.refusal(500)).isNull();
    }
}
