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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * What the player is given to open: a bare {@code qtc_…} ID (or {@code qtb_…}, a record of a
 * basic account without certificate), a {@code /verify/<id>} link, an
 * anonymous share link ({@code /share/g/<token>}), or a local file. qtrace.ca hands a record's
 * content only to who may read it: the licence goes with every request, a share link's token too.
 * No network, no JavaFX: unit-tested; {@link QTraceReplayEngine} does the calls.
 *
 * @param certId null for a local file, and for a share link until qtrace.ca has resolved it
 * @param token  the share link's token, else null
 */
record RecordSource(String certId, String token, boolean local) {

    private static final String VERIFY = "/verify/";
    private static final String SHARE = "/share/g/";

    static RecordSource parse(String source) {
        String s = source.strip();
        if (isRecordId(s)) return new RecordSource(s, null, false);
        // A workflow published on qtrace.ca, possibly with the packet to play (qtw_…#<packet id>).
        if (s.startsWith(WorkflowSource.ID_PREFIX)) {
            int hash = s.indexOf('#');
            return new RecordSource(hash > 0 ? s.substring(0, hash) : s, null, false);
        }
        if (s.startsWith("http")) {
            String token = segmentAfter(s, SHARE);
            if (token != null) return new RecordSource(null, token, false);
            String id = segmentAfter(s, VERIFY);
            // Kept as it was before share links: an unknown URL is taken as an ID and fails upstream.
            return new RecordSource(id != null ? id : s, null, false);
        }
        return new RecordSource(null, null, true);
    }

    /** A certified record's ID (qtc_…) or a basic one's (qtb_…). */
    static boolean isRecordId(String s) {
        return s != null && (s.startsWith("qtc_") || s.startsWith("qtb_"));
    }

    private static String segmentAfter(String url, String marker) {
        int i = url.indexOf(marker);
        if (i < 0) return null;
        String rest = url.substring(i + marker.length());
        int end = rest.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        return end == 0 ? null : rest.substring(0, end);
    }

    /** {@code <base>/api/verify/<certId>/<what>}, with the share token when there is one. */
    static String contentUrl(String base, String certId, String what, String token) {
        // A workflow has its own routes, and its file is a .qtflow where a record's is a .qtrace.
        String url = certId != null && certId.startsWith(WorkflowSource.ID_PREFIX)
            ? base + "/api/workflows/" + certId + "/" + ("qtrace".equals(what) ? "qtflow" : what)
            : base + "/api/verify/" + certId + "/" + what;
        return token == null ? url : url + "?t=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /** The licence is presented to qtrace.ca only — never to the signed storage URLs it hands back. */
    static boolean sendsLicence(String base, String url) {
        return url.startsWith(base + "/");
    }

    /** What to tell the user when qtrace.ca refuses a record, or null for any other failure. */
    static String refusal(int status) {
        return switch (status) {
            case 401 -> "Sign in to qtrace.ca from Extensions > QTrace > Help > Getting started to open this record.";
            case 403 -> "You don't have access to this record or workflow. Ask its owner to share it with you.";
            case 404, 410 -> "Record not found: check the ID, or ask for a new share link.";
            default -> null;
        };
    }
}
