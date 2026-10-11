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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelauncherTest {

    @Test
    void onlyAQuPathLauncherIsRelaunched() {
        assertTrue(Relauncher.isQuPathLauncher("/opt/QuPath/bin/QuPath"));
        assertTrue(Relauncher.isQuPathLauncher("C:\\Program Files\\QuPath-0.7.0\\QuPath-0.7.0.exe"));
        assertTrue(Relauncher.isQuPathLauncher("/Applications/QuPath-0.7.0.app/Contents/MacOS/QuPath-0.7.0"));
        // A QuPath started from a build tool runs as plain java: nothing sensible to start again.
        assertFalse(Relauncher.isQuPathLauncher("/usr/lib/jvm/java-21/bin/java"));
        assertFalse(Relauncher.isQuPathLauncher(""));
        assertFalse(Relauncher.isQuPathLauncher(null));
    }

    @Test
    void unixHelperWaitsForThePidThenStartsTheLauncher() {
        List<String> cmd = Relauncher.helperCommand("Linux", 4242, "/opt/Qu Path/bin/QuPath");
        assertEquals("sh", cmd.get(0));
        assertEquals("-c", cmd.get(1));
        // pid and path are arguments, never pasted into the script: no quoting pitfalls
        assertEquals("4242", cmd.get(3));
        assertEquals("/opt/Qu Path/bin/QuPath", cmd.get(4));
        assertFalse(cmd.get(2).contains("4242"));
        assertFalse(cmd.get(2).contains("Qu Path"));
        assertTrue(cmd.get(2).contains("kill -0"));
    }

    @Test
    void unixHelperGivesUpWhenQuPathStaysOpen() {
        // The user may cancel QuPath's own "save changes?" prompt: no relaunch minutes later.
        assertTrue(Relauncher.helperCommand("Mac OS X", 1, "/x/QuPath").get(2).contains("-lt " + Relauncher.WAIT_HALF_SECONDS));
    }

    @Test
    void windowsHelperIsAnEncodedPowershellCommand() {
        List<String> cmd = Relauncher.helperCommand("Windows 11", 4242, "C:\\Program Files\\QuPath\\QuPath.exe");
        assertEquals("powershell.exe", cmd.get(0));
        assertTrue(cmd.contains("-EncodedCommand"));
        assertTrue(Relauncher.windowsScript(4242, "C:\\It's\\QuPath.exe").contains("'C:\\It''s\\QuPath.exe'"));
        assertTrue(Relauncher.windowsScript(4242, "C:\\QuPath.exe").contains("Wait-Process -Id 4242 -Timeout"));
    }
}
