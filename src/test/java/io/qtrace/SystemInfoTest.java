package io.qtrace;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SystemInfoTest {

    @Test
    void describesTheWorkstation(@TempDir Path project) {
        JsonObject s = SystemInfo.collect(project);
        for (String key : List.of("os", "cpuCores", "ram", "jvmMemory", "java", "locale", "timezone", "projectDisk"))
            assertTrue(s.has(key) && !s.get(key).getAsString().isBlank(), key);
        assertTrue(s.get("os").getAsString().contains(System.getProperty("os.name")));
        assertEquals(String.valueOf(Runtime.getRuntime().availableProcessors()), s.get("cpuCores").getAsString());
    }

    @Test
    void neverCarriesTheUserTheMachineOrAPath(@TempDir Path project) {
        String all = SystemInfo.collect(project).toString();
        assertFalse(all.contains(project.toString()), "project path");
        String home = System.getProperty("user.home");
        if (home != null && home.length() > 3) assertFalse(all.contains(home), "home directory");
        for (String key : SystemInfo.collect(project).keySet())
            assertFalse(key.toLowerCase().contains("host") || key.toLowerCase().contains("user"), key);
    }

    @Test
    void worksWithoutAProject() {
        JsonObject s = SystemInfo.collect(null);
        assertTrue(s.has("os"));
        assertFalse(s.has("projectDisk"));
    }

    @Test
    void environmentLeavesOutWhatIsMissing() {
        JsonObject env = SystemInfo.environment("1.2.4", null, "Linux", null, null, List.of());
        assertEquals("1.2.4", env.get("appVersion").getAsString());
        assertFalse(env.has("qupathVersion"));
        assertFalse(env.has("system"));
        assertFalse(env.has("extensions"));
        assertFalse(env.has("extensionFiles"));
    }

    @Test
    void workstationUpdateIsTheEnvironmentAlone() {
        JsonObject env = SystemInfo.environment("1.2.4", "0.7.0", "Linux", SystemInfo.collect(null), null, null);
        JsonObject body = WorkstationClient.body(env);
        assertEquals(1, body.size());
        assertTrue(body.getAsJsonObject("env").has("system"));
    }

    @Test
    void sizesReadInGigabytes() {
        assertEquals("16.0 GB", SystemInfo.gb(16L * 1024 * 1024 * 1024));
        assertEquals("0.5 GB", SystemInfo.gb(512L * 1024 * 1024));
    }

    @Test
    void cpuModelIsReadFromLinuxCpuinfo() {
        String cpuinfo = "processor\t: 0\nvendor_id\t: GenuineIntel\n"
            + "model name\t: Intel(R) Core(TM) i7-12700H\nprocessor\t: 1\nmodel name\t: Intel(R) Core(TM) i7-12700H\n";
        assertEquals("Intel(R) Core(TM) i7-12700H", SystemInfo.cpuModelFromCpuinfo(cpuinfo));
        assertNull(SystemInfo.cpuModelFromCpuinfo("processor\t: 0\n"));
    }

    @Test
    void installedFilesAreListedByNameOnly(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("qupath-extension-instanseg-0.1.6.jar"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.createDirectory(dir.resolve("qtrace"));
        Files.writeString(dir.resolve("qtrace").resolve("qtrace-core-1.2.3.qtjar"), "x");
        assertEquals(List.of("qupath-extension-instanseg-0.1.6.jar"), SystemInfo.fileNames(dir, ".jar"));
        assertEquals(List.of("qtrace-core-1.2.3.qtjar"), SystemInfo.fileNames(dir.resolve("qtrace"), ".qtjar"));
        assertEquals(List.of(), SystemInfo.fileNames(dir.resolve("missing"), ".jar"));
        assertEquals(List.of(), SystemInfo.fileNames(null, ".jar"));
    }
}
