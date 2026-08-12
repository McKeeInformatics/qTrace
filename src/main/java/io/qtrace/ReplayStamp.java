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

import io.qtrace.chain.CanonicalJson;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immutable record of one qTrace replay execution — the "proof of execution" companion
 * to {@link ValidationStamp}. Where a ValidationStamp attests that a validator reviewed
 * a result, a ReplayStamp attests that a specific .qtrace was mechanically re-executed,
 * with what outcome.
 *
 * {@code sourceQtraceHash} is the attachment key: given a .qtrace's hash, every
 * ReplayStamp ever produced for it can be found without the stamp being a node in any
 * version graph — see the UX-Re-Player brief.
 *
 * Same signing convention as ValidationStamp: {@code signature}/{@code validatorKeyPub}
 * are ED25519 over {@link #canonicalPayload()}, produced by
 * {@link StampSigner#signPayload(String)}. Null signature = unsigned (no Compliance license
 * active at stamp time — same degradation as ValidationStamp).
 */
public record ReplayStamp(
    String  replayer,
    Instant timestamp,
    String  sourceQtraceHash,
    String  logHash,
    String  qupathVersion,
    String  resultStatus,          // "OK" / "NOK"
    int     stepsTotal,
    int     stepsOk,
    int     stepsFailed,
    int     stepsSkippedIncompat,
    int     stepsSkippedUser,
    List<Integer> excludedSteps,   // step indices unchecked by the user before this run
    String  signature,
    String  validatorKeyPub
) {
    /** RFC 8785 (JCS) canonical JSON payload that is signed — same convention as ValidationStamp. */
    public String canonicalPayload() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("replayer",                nvl(replayer));
        fields.put("timestamp",               timestamp != null ? timestamp.toString() : "");
        fields.put("source_qtrace_hash",      nvl(sourceQtraceHash));
        fields.put("log_hash",                nvl(logHash));
        fields.put("qupath_version",          nvl(qupathVersion));
        fields.put("result_status",           nvl(resultStatus));
        fields.put("steps_total",             String.valueOf(stepsTotal));
        fields.put("steps_ok",                String.valueOf(stepsOk));
        fields.put("steps_failed",            String.valueOf(stepsFailed));
        fields.put("steps_skipped_incompat",  String.valueOf(stepsSkippedIncompat));
        fields.put("steps_skipped_user",      String.valueOf(stepsSkippedUser));
        fields.put("excluded_steps",          excludedSteps == null || excludedSteps.isEmpty()
            ? "" : excludedSteps.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
        fields.put("validator_key_pub",       nvl(validatorKeyPub));
        return CanonicalJson.of(fields);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
