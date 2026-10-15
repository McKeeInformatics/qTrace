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

import io.qtrace.QTraceController;
import io.qtrace.QTracePluginManager;
import io.qtrace.ReplayStamp;
import io.qtrace.StampSigner;
import io.qtrace.chain.Hashing;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes one replay run's log in flow — appended after every step, not buffered to the
 * end — so a crash mid-replay still leaves an exploitable trace. Ends with a signed
 * {@link ReplayStamp} footer when a Compliance license is active (see {@link #finish}).
 *
 * File: {@code <logsDir>/LOG_<baseName>_<yyyyMMdd-HHmmss>.log}, one file per run — or
 * {@code LOG_<baseName>_<targetImageName>_<yyyyMMdd-HHmmss>.log} when the run is one leg of a
 * Target image(s) batch, so N logs produced within the same batch stay individually identifiable
 * by filename alone instead of only by timestamp. {@code logsDir} itself is the caller's call —
 * see {@code QTraceConfig.getLogsDir()} / Project Folder mode in {@code QTraceReplayPlayer}.
 */
public class ReplayLogWriter implements AutoCloseable {

    private static final DateTimeFormatter TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path logPath;
    private final Writer writer;
    private final Instant startedAt;
    private final List<Integer> excludedSteps;

    private final String machine = hostName();
    /** SHA-256 of the log through its summary (what the stamp signs as {@code log_hash}); set by {@link #finishUnsigned}. */
    private String summaryHash;

    private int stepsOk, stepsFailed, stepsSkippedIncompat, stepsSkippedUser;

    /**
     * @param targetImageName the batch target this run replayed against, or {@code null} outside a Target image(s) batch.
     * @param batchTargets    every image checked in Target image(s) for this Play click (in play order), or
     *                        {@code null} outside a batch — lets one image's log answer "which images were part
     *                        of this run" without needing a separate combined summary file.
     * @param batchPosition   1-based position of {@code targetImageName} within {@code batchTargets}, or
     *                        {@code null} outside a batch — read across a batch's log files in order, this is
     *                        the trail of "moved from image N to image N+1".
     * @param logsDir         directory this run's log file is written into (created if missing) — the caller
     *                        resolves this (configured Logs directory, or {@code <project>/qTrace/Logs} in
     *                        Project Folder mode), this class just writes into whatever it's given.
     */
    public ReplayLogWriter(String qtraceBaseName, String sourceQtraceHash, String qupathVersion,
            String compatSummary, List<Integer> excludedSteps, String targetImageName,
            List<String> batchTargets, Integer batchPosition, Path logsDir) throws IOException {
        this.startedAt = Instant.now();
        this.excludedSteps = excludedSteps;

        Path dir = logsDir;
        Files.createDirectories(dir);
        String safeBase = qtraceBaseName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        String safeTarget = targetImageName != null
            ? "_" + targetImageName.replaceAll("[^a-zA-Z0-9_.-]", "_") : "";
        this.logPath = dir.resolve("LOG_" + safeBase + safeTarget + "_" + TS_FORMAT.format(startedAt) + ".log");
        this.writer = Files.newBufferedWriter(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        writeLine("=== qTrace Replay Log ===");
        writeLine("started        : " + startedAt);
        writeLine("qtrace         : " + qtraceBaseName);
        writeLine("source_hash    : " + sourceQtraceHash);
        writeLine("qupath_version : " + qupathVersion);
        writeLine("qtrace_version : " + QTraceController.VERSION);
        writeLine("machine        : " + machine);
        if (targetImageName != null) writeLine("target_image   : " + targetImageName);
        if (batchTargets != null && !batchTargets.isEmpty()) {
            writeLine("batch_position : " + batchPosition + "/" + batchTargets.size());
            writeLine("batch_targets  : " + String.join(" | ", batchTargets));
        }
        writeLine("compat         : " + compatSummary);
        writeLine("excluded_steps : " + (excludedSteps == null || excludedSteps.isEmpty()
            ? "none" : excludedSteps.stream().map(String::valueOf).collect(Collectors.joining(","))));
        writeLine("");
        writeLine("--- steps ---");
        flush();
    }

    public Path logPath() { return logPath; }

    /** Hash of the log as it stood at the end of its summary — stable: what is appended later (batch note, stamp) is not in it. Null before the summary. */
    public String summaryHash() { return summaryHash; }

    /**
     * Ties the .qtrace about to be written to this log: {@code log_file} and {@code log_sha256}
     * go into its {@code replayed_from}. Needs the summary written ({@link #finishUnsigned}) —
     * until then only the file name is known, and the hash is left out.
     */
    public void linkInto(JsonObject replayedFrom) {
        if (replayedFrom == null) return;
        replayedFrom.addProperty("log_file", logPath.getFileName().toString());
        if (summaryHash != null) replayedFrom.addProperty("log_sha256", summaryHash);
    }

    static String hostName() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    /**
     * Marks, at the tail of this (about-to-be-closed) image's log, the moment the Target
     * image(s) batch moves on to the next target — the on-screen Console gets the equivalent
     * line too, but that view is ephemeral; this is what survives in the file.
     */
    public void appendBatchTransition(String nextTargetName) throws IOException {
        writeLine("");
        writeLine("=== batch: moving to next target — " + nextTargetName + " ===");
        flush();
    }

    /** Appends one step's outcome and flushes immediately. */
    /**
     * Steps unchecked in Instructions ({@code SKIPPED_EXCLUDED}) still count toward the summary
     * and are still listed by number in the header's {@code excluded_steps} line, but don't get
     * their own line in the step-by-step body — with hundreds of steps and only a handful
     * actually run, one line per excluded step buried the ones that matter.
     */
    public void appendStep(ReplayStep step, ReplayExecutor.StepResult result) throws IOException {
        switch (result.status()) {
            case OK                  -> stepsOk++;
            case FAILED               -> stepsFailed++;
            case SKIPPED_INCOMPATIBLE -> stepsSkippedIncompat++;
            case SKIPPED_EXCLUDED     -> stepsSkippedUser++;
        }
        if (result.status() == ReplayExecutor.Status.SKIPPED_EXCLUDED) return;
        writeLine(String.format("[Step %d] %-16s %-30s %-20s (%dms)%s",
            step.index(), step.type(), truncate(step.title(), 30), result.status(),
            result.durationMs(),
            result.errorMessage() != null ? " — " + result.errorMessage() : ""));
        flush();
    }

    /**
     * Writes the summary footer (verdict, durations, step counts) and returns the
     * corresponding {@link ReplayStamp} unsigned — {@code signature() == null}. This is
     * the automatic, system-produced side: "it ran, here's what happened." The writer stays
     * open so {@link #appendSignedStamp} can append the human-triggered signature afterwards
     * (Player's gold "Stamper" button) without reopening the file.
     */
    public ReplayStamp finishUnsigned(String replayer, String sourceQtraceHash, String qupathVersion) throws Exception {
        Instant endedAt = Instant.now();
        int total = stepsOk + stepsFailed + stepsSkippedIncompat + stepsSkippedUser;
        String resultStatus = stepsFailed == 0 ? "OK" : "NOK";

        writeLine("");
        writeLine("=== summary ===");
        writeLine("ended          : " + endedAt);
        writeLine("duration_ms    : " + Duration.between(startedAt, endedAt).toMillis());
        writeLine("result         : Replay " + resultStatus + " (" + stepsFailed + " error"
            + (stepsFailed == 1 ? "" : "s") + ")");
        writeLine("steps_total    : " + total);
        writeLine("steps_ok       : " + stepsOk);
        writeLine("steps_failed   : " + stepsFailed);
        writeLine("steps_skipped_incompat : " + stepsSkippedIncompat);
        writeLine("steps_skipped_user     : " + stepsSkippedUser);
        flush();

        String logHash = Hashing.sha256Hex(logPath);
        this.summaryHash = logHash;
        String validatorKeyPub = QTracePluginManager.isEntitled()
            ? QTracePluginManager.getEntitled().getActiveLicenseInfo().validatorKey()
            : null;
        return new ReplayStamp(
            replayer, endedAt, sourceQtraceHash, logHash, qupathVersion, resultStatus,
            total, stepsOk, stepsFailed, stepsSkippedIncompat, stepsSkippedUser,
            excludedSteps, null, validatorKeyPub, machine);
    }

    /**
     * The human-triggered half: signs {@code unsigned}'s canonical payload (ED25519, same
     * signing key as ValidationStamp — the caller is responsible for having unlocked it via
     * {@code promptPassphraseAndDecrypt} beforehand), appends the signed block to the log,
     * and closes the writer.
     *
     * @return the signed ReplayStamp, or {@code null} if no signing key was available
     *         (unsigned log — same degradation as an unsigned ValidationStamp).
     */
    public ReplayStamp appendSignedStamp(ReplayStamp unsigned) throws IOException {
        String signature = StampSigner.signPayload(unsigned.canonicalPayload());
        ReplayStamp result = signature == null ? null : unsigned.withSignature(signature);

        writeLine("");
        writeLine("=== ReplayStamp ===");
        writeLine(result != null ? "signed         : yes" : "signed         : no (no signing key available)");
        writeLine("payload        : " + unsigned.canonicalPayload());
        if (result != null) {
            writeLine("signature      : " + signature);
            writeLine("validator_key  : " + unsigned.validatorKeyPub());
        }
        flush();
        close();
        return result;
    }

    private void writeLine(String line) throws IOException {
        writer.write(line);
        writer.write("\n");
    }

    private void flush() throws IOException { writer.flush(); }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public void close() throws IOException { writer.close(); }
}
