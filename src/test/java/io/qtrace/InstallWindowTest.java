package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstallWindowTest {

    @AfterEach
    void clear() {
        InstallWindow.clear();
    }

    @Test
    void withoutAModuleBringingItThereIsNoWindow() {
        assertNull(InstallWindow.open());
    }

    @Test
    void theRegisteredWindowOpensEachTime() {
        int[] opened = {0};
        InstallWindow.Progress progress = new InstallWindow.Progress() {
            public void step(String module, String title, String version, double fraction) {}
            public void done(int modules) {}
        };
        InstallWindow.register(() -> { opened[0]++; return progress; });
        assertSame(progress, InstallWindow.open());
        assertSame(progress, InstallWindow.open());
        assertEquals(2, opened[0]);
    }

    @Test
    void registeringNothingKeepsTheWindow() {
        InstallWindow.Progress progress = new InstallWindow.Progress() {
            public void step(String module, String title, String version, double fraction) {}
            public void done(int modules) {}
        };
        InstallWindow.register(() -> progress);
        InstallWindow.register(null);
        assertSame(progress, InstallWindow.open());
    }
}
