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
package io.qtrace.welcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * "Quit &amp; Relaunch QuPath now": starts a detached helper that waits for this QuPath to exit,
 * then starts it again from the same launcher. The helper gives up after a minute: if the user
 * cancels QuPath's own "save changes?" prompt, QuPath must not reopen by itself later.
 * Only a QuPath launcher is started again (a QuPath run from a build tool is plain {@code java}).
 */
final class Relauncher {

    private static final Logger log = LoggerFactory.getLogger(Relauncher.class);
    /** How long the helper waits for QuPath to exit, in half seconds. */
    static final int WAIT_HALF_SECONDS = 120;

    private Relauncher() {}

    /** Schedules the relaunch of the running QuPath; false when it cannot be (the quit still happens). */
    static boolean schedule() {
        try {
            String launcher = ProcessHandle.current().info().command().orElse(null);
            if (!isQuPathLauncher(launcher)) {
                log.info("[qtrace-welcome] no relaunch: not a QuPath launcher ({})", launcher);
                return false;
            }
            new ProcessBuilder(helperCommand(System.getProperty("os.name", ""), ProcessHandle.current().pid(), launcher))
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            log.info("[qtrace-welcome] relaunch scheduled: {}", launcher);
            return true;
        } catch (Exception e) {
            log.info("[qtrace-welcome] no relaunch: {}", e.toString());
            return false;
        }
    }

    static boolean isQuPathLauncher(String command) {
        if (command == null || command.isBlank()) return false;
        String name = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1);
        return name.toLowerCase(Locale.ROOT).startsWith("qupath");
    }

    static List<String> helperCommand(String os, long pid, String launcher) {
        if (os.toLowerCase(Locale.ROOT).startsWith("windows")) {
            // -EncodedCommand (UTF-16LE base64): no quoting pitfalls with user paths.
            String encoded = Base64.getEncoder().encodeToString(windowsScript(pid, launcher).getBytes(StandardCharsets.UTF_16LE));
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
        }
        // $0 = pid, $1 = launcher. Ignore SIGHUP so closing QuPath's terminal doesn't kill us.
        return List.of("sh", "-c",
            "trap '' HUP; i=0; while kill -0 \"$0\" 2>/dev/null && [ $i -lt " + WAIT_HALF_SECONDS + " ]; do sleep 0.5; i=$((i+1)); done; "
            + "kill -0 \"$0\" 2>/dev/null || exec \"$1\"",
            Long.toString(pid), launcher);
    }

    static String windowsScript(long pid, String launcher) {
        return "$ErrorActionPreference='SilentlyContinue'\n"
            + "Wait-Process -Id " + pid + " -Timeout " + (WAIT_HALF_SECONDS / 2) + "\n"
            + "if (-not (Get-Process -Id " + pid + ")) { Start-Process -FilePath '" + launcher.replace("'", "''") + "' }\n";
    }

    private static File nullDevice() {
        return Path.of(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null").toFile();
    }
}
