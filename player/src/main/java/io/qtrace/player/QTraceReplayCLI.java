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

import io.qtrace.player.ext.ExtensionHandlerRegistry;
import io.qtrace.player.ext.HandlerRegistrar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Standalone CLI entry point for replaying a .qtrace file.
 *
 * Usage:
 *   java -jar qtrace-replay-cli-<version>.jar <source> [options]
 *
 *   source       Local .qtrace path, /verify/qtc_... URL, or bare qtc_... ID
 *   --out FILE   Output .groovy path (default: replay_<image>_<timestamp>.groovy in cwd)
 *   --session N  Session index to replay (default: all sessions)
 *   --print      Print script to stdout instead of writing a file
 */
public class QTraceReplayCLI {

    public static void main(String[] args) {
        HandlerRegistrar.registerAll(ExtensionHandlerRegistry.get());
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            System.exit(0);
        }

        String source      = null;
        String outFile     = null;
        int    sessionIdx  = -1;
        boolean printOnly  = false;
        boolean validatedOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out":
                    if (i + 1 >= args.length) die("--out requires a file path");
                    outFile = args[++i];
                    break;
                case "--session":
                    if (i + 1 >= args.length) die("--session requires an integer");
                    try { sessionIdx = Integer.parseInt(args[++i]); }
                    catch (NumberFormatException e) { die("--session value must be an integer"); }
                    break;
                case "--print":
                    printOnly = true;
                    break;
                case "--validated-only":
                    validatedOnly = true;
                    break;
                default:
                    if (args[i].startsWith("--")) die("Unknown option: " + args[i]);
                    if (source != null) die("Multiple sources specified");
                    source = args[i];
            }
        }

        if (source == null) die("No source specified. Run with --help for usage.");

        try {
            System.err.println("[qtrace-replay] Downloading .qtrace from: " + source);
            String qtraceJson = QTraceReplayEngine.downloadQtrace(source);

            // Download classifiers if source references a workspace bundle
            Path classifierDir = null;
            String certId = null;
            if (QTraceReplayEngine.isCertificateSource(source)) {
                certId = QTraceReplayEngine.extractCertId(source);
            }
            if (certId != null) {
                System.err.println("[qtrace-replay] Downloading classifiers for " + certId + "...");
                classifierDir = Files.createTempDirectory("qtrace_classifiers_");
                try {
                    QTraceReplayEngine.downloadClassifiers(certId, classifierDir);
                } catch (Exception e) {
                    System.err.println("[qtrace-replay] Warning: could not download classifiers: " + e.getMessage());
                    classifierDir = null;
                }
            }

            System.err.println("[qtrace-replay] Generating Groovy script...");
            String script = QTraceReplayEngine.generateGroovyScript(qtraceJson, classifierDir, sessionIdx, validatedOnly);

            if (printOnly) {
                System.out.print(script);
            } else {
                Path out = outFile != null ? Path.of(outFile) : defaultOutputPath(qtraceJson);
                Files.writeString(out, script);
                System.err.println("[qtrace-replay] Written: " + out.toAbsolutePath());
                System.err.println("[qtrace-replay] Open in QuPath: Edit → Run Script");
            }

            if (classifierDir != null) {
                deleteDirectory(classifierDir);
            }

        } catch (Exception e) {
            System.err.println("[qtrace-replay] Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Path defaultOutputPath(String qtraceJson) {
        String imageName = "unknown";
        try {
            int nameIdx = qtraceJson.indexOf("\"name\":\"");
            if (nameIdx >= 0) {
                int start = nameIdx + 8;
                int end = qtraceJson.indexOf('"', start);
                if (end > start) imageName = qtraceJson.substring(start, end)
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            }
        } catch (Exception ignored) {}
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        return Path.of("replay_" + imageName + "_" + ts + ".groovy");
    }

    private static void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private static void die(String message) {
        System.err.println("[qtrace-replay] " + message);
        printUsage();
        System.exit(1);
    }

    private static void printUsage() {
        System.err.println("""
            Usage: java -jar qtrace-replay-cli-<version>.jar <source> [options]

              source        Local .qtrace path, verify URL, or qtc_... ID
              --out <file>  Output .groovy path (default: replay_<image>_<timestamp>.groovy)
              --session <n> Session index to replay, 0-based (default: all sessions)
              --print       Print script to stdout instead of writing a file
              --validated-only  Leave out instructions recorded only in unstamped sessions
              --help        Show this help

            Examples:
              qtrace-replay ./analysis.qtrace
              qtrace-replay qtc_01KV8HFHEAQ76SDWVNXDNXMW9P --out replay.groovy
              qtrace-replay https://qtrace.ca/verify/qtc_01KV8HFHEAQ76SDWVNXDNXMW9P
            """);
    }
}
