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

package io.qtrace.player.ext;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstanSegHandler implements QTraceExtensionHandler {

    private static final Pattern MODEL_PATH_PAT =
        Pattern.compile("\\.modelPath\\(\"([^\"]*)\"\\)");

    @Override public String getExtensionName() { return "instanseg"; }
    @Override public String getInstallUrl() {
        return "https://github.com/qupath/qupath-extension-instanseg/releases";
    }
    @Override public Compat getCompat()  { return Compat.PARTIAL; }
    @Override public String getCaveat() {
        return "Model must be downloaded before running the script.\n"
             + "In QuPath: Extensions › InstanSeg › Download models\n"
             + "The pre-flight dialog will flag the model if it is missing.";
    }

    /** How many directory levels to descend when hunting for a model folder under a root. */
    private static final int MAX_SEARCH_DEPTH = 6;

    @Override
    public String buildPreambleGroovy() {
        StringBuilder sb = new StringBuilder();
        sb.append("System.setProperty('ai.djl.offline', 'false');\n\n");
        // Ask the InstanSeg extension itself (loaded live, in this QuPath) where IT thinks models
        // live on THIS machine — 'instanseg.model.dir' is a per-machine preference, so the value
        // recorded in the .qtrace file (captured on a possibly different machine) can't be trusted.
        sb.append("def _qtraceInstanSegLocalRoots = {\n");
        sb.append("    def _roots = []\n");
        sb.append("    try {\n");
        sb.append("        def _prefsCls = this.class.classLoader.loadClass('qupath.ext.instanseg.ui.InstanSegPreferences')\n");
        sb.append("        def _m = _prefsCls.getDeclaredMethod('modelDirectoryProperty')\n");
        sb.append("        _m.setAccessible(true)\n");
        sb.append("        def _val = _m.invoke(null)?.get()\n");
        sb.append("        if (_val) _roots << new java.io.File(_val.toString())\n");
        sb.append("    } catch (Throwable _e_pref) {\n");
        sb.append("        println('[QTrace] InstanSeg local model dir lookup failed: ' + _e_pref.message)\n");
        sb.append("    }\n");
        sb.append("    return _roots\n");
        sb.append("};\n");
        // Diagnostic + warm-up: InstanSeg's own model registry (Watcher) is a lazily-created
        // singleton. If nothing in this QuPath session has touched it yet (e.g. the InstanSeg
        // panel was never opened), asking it for its state is what actually creates/populates it —
        // the same trigger that happens when a user opens the panel by hand. Do this before any
        // filesystem search, and log what the extension itself reports, so a future 'model vanished'
        // report has real evidence instead of guesswork.
        sb.append("def _qtraceInstanSegProbe = { String _mn ->\n");
        sb.append("    try {\n");
        sb.append("        def _watcherCls = this.class.classLoader.loadClass('qupath.ext.instanseg.ui.Watcher')\n");
        sb.append("        def _getInstance = _watcherCls.getDeclaredMethod('getInstance')\n");
        sb.append("        _getInstance.setAccessible(true)\n");
        sb.append("        def _watcher = _getInstance.invoke(null)\n");
        sb.append("        def _getModels = _watcherCls.getDeclaredMethod('getModels')\n");
        sb.append("        _getModels.setAccessible(true)\n");
        sb.append("        def _names = _getModels.invoke(_watcher).collect { it.getName() }\n");
        sb.append("        println('[QTrace][InstanSeg diag] extension registry knows ' + _names.size() + ' model(s): ' + _names)\n");
        sb.append("        println('[QTrace][InstanSeg diag] extension registry already tracking \"' + _mn + '\": ' + _names.any { it?.toString()?.startsWith(_mn) })\n");
        sb.append("    } catch (Throwable _e_probe) {\n");
        sb.append("        println('[QTrace][InstanSeg diag] extension registry probe failed: ' + _e_probe.message)\n");
        sb.append("    }\n");
        sb.append("};\n");
        // Actual on-disk layout under a root can nest an arbitrary number of 'downloaded/' levels
        // deep (observed: model dir itself, one level, two levels...) — depending on how the
        // extension's model directory was reconfigured over time. Don't guess the depth: search for it.
        sb.append("def _qtraceInstanSegFindInRoot = { java.io.File _root, String _mn ->\n");
        sb.append("    if (!_root || !_root.exists()) return null\n");
        sb.append("    try {\n");
        sb.append("        return java.nio.file.Files.walk(_root.toPath(), " + MAX_SEARCH_DEPTH + ")\n");
        sb.append("            .filter { java.nio.file.Files.isDirectory(it) }\n");
        sb.append("            .filter { it.fileName?.toString()?.startsWith(_mn) }\n");
        sb.append("            .filter { java.nio.file.Files.exists(it.resolve('rdf.yaml')) }\n");
        sb.append("            .findFirst()\n");
        sb.append("            .map { it.toFile() }\n");
        sb.append("            .orElse(null)\n");
        sb.append("    } catch (Throwable _e_walk) { return null }\n");
        sb.append("};\n");
        // Exact-version counterpart of the closure above — matches the directory name in full
        // (version included) rather than by prefix. Tried first, so a model with the exact
        // requested version always wins over a differently-versioned one that merely shares the
        // base name (e.g. a leftover download from an earlier run).
        sb.append("def _qtraceInstanSegFindExactInRoot = { java.io.File _root, String _exactName ->\n");
        sb.append("    if (!_root || !_root.exists()) return null\n");
        sb.append("    try {\n");
        sb.append("        return java.nio.file.Files.walk(_root.toPath(), " + MAX_SEARCH_DEPTH + ")\n");
        sb.append("            .filter { java.nio.file.Files.isDirectory(it) }\n");
        sb.append("            .filter { it.fileName?.toString() == _exactName }\n");
        sb.append("            .filter { java.nio.file.Files.exists(it.resolve('rdf.yaml')) }\n");
        sb.append("            .findFirst()\n");
        sb.append("            .map { it.toFile() }\n");
        sb.append("            .orElse(null)\n");
        sb.append("    } catch (Throwable _e_walk) { return null }\n");
        sb.append("};\n");
        // No "def" here: rewriteStepGroovy() below makes step fragments call this closure from
        // a *separate* GroovyShell.evaluate() call (the Player runs preamble and each step
        // independently against a shared Binding). A `def`-declared closure is local to the
        // script that declares it and would vanish once the preamble's evaluate() returns — the
        // undeclared/bare assignment writes through to the shared Binding instead, so later
        // steps can still find it by name. The three helper closures above stay `def`: they're
        // only ever called from inside this closure's own body, in the same preamble script.
        sb.append("_qtraceInstanSegPath = { String _mn, String _orig ->\n");
        sb.append("    _qtraceInstanSegProbe(_mn)\n");
        sb.append("    def _h = System.getProperty('user.home')\n");
        sb.append("    def _roots = _qtraceInstanSegLocalRoots() + [\n");
        sb.append("        new java.io.File(_h, 'QuPath/v0.7/instanseg'),\n");
        sb.append("        new java.io.File(_h, '.qupath/v0.7/instanseg'),\n");
        sb.append("        new java.io.File(_h, 'QuPath/v0.6/instanseg'),\n");
        sb.append("        new java.io.File(_h, '.qupath/v0.6/instanseg')\n");
        sb.append("    ]\n");
        sb.append("    def _exactName = _orig ? new java.io.File(_orig).name : null\n");
        sb.append("    def _found = null\n");
        sb.append("    if (_exactName) {\n");
        sb.append("        for (def _root : _roots) {\n");
        sb.append("            _found = _qtraceInstanSegFindExactInRoot(_root, _exactName)\n");
        sb.append("            if (_found) break\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    if (_found) {\n");
        sb.append("        println('[QTrace] InstanSeg model resolved: ' + _found.absolutePath)\n");
        sb.append("        return _found.absolutePath\n");
        sb.append("    }\n");
        sb.append("    def _foundOtherVersion = null\n");
        sb.append("    for (def _root : _roots) {\n");
        sb.append("        _foundOtherVersion = _qtraceInstanSegFindInRoot(_root, _mn)\n");
        sb.append("        if (_foundOtherVersion) break\n");
        sb.append("    }\n");
        sb.append("    if (_foundOtherVersion) {\n");
        sb.append("        println('[QTrace] WARNING: requested model version \"' + (_exactName ?: _mn) + '\" not found — using a different version installed locally instead: ' + _foundOtherVersion.absolutePath + ' — results may differ from the original run. Download the exact version via Extensions > InstanSeg > Download models.')\n");
        sb.append("        return _foundOtherVersion.absolutePath\n");
        sb.append("    }\n");
        sb.append("    if (_orig && new java.io.File(_orig).exists()) {\n");
        sb.append("        println('[QTrace] InstanSeg model resolved (original recorded path, unverified on this machine): ' + _orig)\n");
        sb.append("        return _orig\n");
        sb.append("    }\n");
        sb.append("    println('[QTrace] WARNING: InstanSeg model not found locally for: ' + _mn + ' — download it via Extensions > InstanSeg > Download models')\n");
        sb.append("    return _mn\n");
        sb.append("};\n");
        return sb.toString();
    }

    @Override
    public String rewriteStepGroovy(String fragment) {
        Matcher m = MODEL_PATH_PAT.matcher(fragment);
        StringBuffer buf = new StringBuffer();
        while (m.find()) {
            String full = m.group(1);
            int lastSep = Math.max(full.lastIndexOf('/'), full.lastIndexOf('\\'));
            String modelName = lastSep >= 0 ? full.substring(lastSep + 1) : full;
            String baseName  = modelName.replaceAll("-v?\\d+[\\d.]*$", "");
            m.appendReplacement(buf, Matcher.quoteReplacement(
                ".modelPath(_qtraceInstanSegPath(\"" + baseName + "\", \"" + full + "\"))"));
        }
        m.appendTail(buf);
        return buf.toString();
    }

    @Override
    public boolean isPrerequisiteMet(String modelBaseName) {
        return isPrerequisiteMet(modelBaseName, null);
    }

    /**
     * Same check, but resolved against THIS machine's local state rather than the .qtrace file:
     * first the InstanSeg extension's own live 'instanseg.model.dir' preference, then the
     * conventional default install locations, and only then the path recorded at capture time
     * (which was captured on whichever machine ran the original analysis, and may not apply here).
     *
     * <p>Never matched against {@code originalPath} itself as a filesystem location — that path is
     * whatever the capturing machine's InstanSeg model directory happened to be, and searching for
     * it literally on this machine would only work by coincidence (or worse, resolve to an
     * unrelated directory that happens to exist at the same relative location). Matching is always
     * by model name, against roots discovered on this machine; {@code originalPath} is used only
     * to read off the recorded version suffix (via {@link #exactDirName}).
     *
     * <p>A different version of the same model, already downloaded locally, still counts as met —
     * see {@link #getModelStatus(String, String)} for the distinction, surfaced as a warning by
     * the pre-flight rather than a "missing" result.
     */
    @Override
    public boolean isPrerequisiteMet(String modelBaseName, String originalPath) {
        return getModelStatus(modelBaseName, originalPath) != ModelStatus.MISSING;
    }

    /**
     * Tri-state version of {@link #isPrerequisiteMet(String, String)}: distinguishes an exact
     * version match (PRESENT) from a differently-versioned local download of the same model
     * (VERSION_MISMATCH, still usable but worth flagging — results may differ from the original
     * run) from nothing found at all (MISSING). Matching is always by model directory name against
     * roots discovered on this machine — never against the literal path recorded in the .qtrace,
     * which belongs to whichever machine captured the run.
     */
    @Override
    public ModelStatus getModelStatus(String modelBaseName, String originalPath) {
        return resolve(modelBaseName, originalPath).status();
    }

    /**
     * Directory name of the model actually found on this machine (exact match or version
     * mismatch), e.g. "fluorescence_nuclei_and_cells-0.1.1". Null when nothing was found. Purely
     * for display — lets the pre-flight show the source vs. locally-installed version side by side
     * instead of just "different version installed" so the user can judge the gap themselves.
     */
    @Override
    public String findLocalModelVersion(String modelBaseName, String originalPath) {
        File found = resolve(modelBaseName, originalPath).found();
        return found != null ? found.getName() : null;
    }

    private record Resolution(ModelStatus status, File found) {}

    private static Resolution resolve(String modelBaseName, String originalPath) {
        String exactDirName = exactDirName(originalPath);
        List<File> roots = allRoots();
        if (exactDirName != null) {
            for (File root : roots) {
                File f = findExactInRoot(root, exactDirName);
                if (f != null) return new Resolution(ModelStatus.PRESENT, f);
            }
        }
        for (File root : roots) {
            File f = findInRoot(root, modelBaseName);
            if (f != null) return new Resolution(ModelStatus.VERSION_MISMATCH, f);
        }
        return new Resolution(ModelStatus.MISSING, null);
    }

    /** The last path segment of a recorded model path, e.g. "foo-0.1.0". Null if unavailable. Display use only. */
    public static String lastPathSegment(String path) {
        if (path == null || path.isBlank()) return null;
        int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSep >= 0 ? path.substring(lastSep + 1) : path;
    }

    /** Local model dir preference + every conventional default install location, in lookup order. */
    private static List<File> allRoots() {
        List<File> roots = new ArrayList<>(localModelRoots());
        String home = System.getProperty("user.home");
        String[] candidates = {
            "QuPath/v0.7/instanseg",
            ".qupath/v0.7/instanseg",
            "QuPath/v0.6/instanseg",
            ".qupath/v0.6/instanseg"
        };
        for (String rel : candidates) roots.add(new File(home, rel));
        return roots;
    }

    /** The last path segment of the originally recorded model path, e.g. "foo-0.1.0". Null if unavailable. */
    private static String exactDirName(String originalPath) {
        if (originalPath == null || originalPath.isBlank()) return null;
        int lastSep = Math.max(originalPath.lastIndexOf('/'), originalPath.lastIndexOf('\\'));
        return lastSep >= 0 ? originalPath.substring(lastSep + 1) : originalPath;
    }

    /** Like {@link #findInRoot}, but requires the directory name to match exactly (version included). */
    private static File findExactInRoot(File root, String exactDirName) {
        if (root == null || !root.exists()) return null;
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                 java.nio.file.Files.walk(root.toPath(), MAX_SEARCH_DEPTH)) {
            return walk
                .filter(java.nio.file.Files::isDirectory)
                .filter(p -> p.getFileName() != null && p.getFileName().toString().equals(exactDirName))
                .filter(p -> java.nio.file.Files.exists(p.resolve("rdf.yaml")))
                .findFirst()
                .map(java.nio.file.Path::toFile)
                .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Searches under root for a directory whose name starts with modelBaseName and contains
     * rdf.yaml. The actual nesting depth under a model root varies (observed 1-3 levels of
     * 'downloaded/' depending on how the directory was reconfigured), so this walks bounded-depth
     * rather than assuming a fixed layout.
     */
    private static File findInRoot(File root, String modelBaseName) {
        if (root == null || !root.exists()) return null;
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                 java.nio.file.Files.walk(root.toPath(), MAX_SEARCH_DEPTH)) {
            return walk
                .filter(java.nio.file.Files::isDirectory)
                .filter(p -> p.getFileName() != null && p.getFileName().toString().startsWith(modelBaseName))
                .filter(p -> java.nio.file.Files.exists(p.resolve("rdf.yaml")))
                .findFirst()
                .map(java.nio.file.Path::toFile)
                .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Reflectively asks the live InstanSeg extension (if loaded in this QuPath) where it stores
     * downloaded models on this machine. Package-private class/method — access is bypassed via
     * reflection since both JARs share QuPath's plugin classloader (no module boundary).
     */
    private static List<File> localModelRoots() {
        List<File> roots = new ArrayList<>();
        try {
            Class<?> prefsCls = Class.forName(
                "qupath.ext.instanseg.ui.InstanSegPreferences", true,
                Thread.currentThread().getContextClassLoader());
            java.lang.reflect.Method m = prefsCls.getDeclaredMethod("modelDirectoryProperty");
            m.setAccessible(true);
            Object prop = m.invoke(null);
            Object val = prop == null ? null
                : prop.getClass().getMethod("get").invoke(prop);
            if (val != null && !val.toString().isBlank()) {
                roots.add(new File(val.toString()));
            }
        } catch (Throwable ignored) {
            // InstanSeg not installed/loaded, or its internals changed — fall back silently.
        }
        return roots;
    }

    /** Extract unique model base names from a collection of script fragments. */
    public static List<String> extractModelBaseNames(Collection<String> fragments) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> e : extractModelPaths(fragments).entrySet())
            result.add(e.getKey());
        return result;
    }

    /** Extract unique model base names mapped to their first-seen original recorded path. */
    public static Map<String, String> extractModelPaths(Collection<String> fragments) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String frag : fragments) {
            Matcher m = MODEL_PATH_PAT.matcher(frag);
            while (m.find()) {
                String full = m.group(1);
                int sep = Math.max(full.lastIndexOf('/'), full.lastIndexOf('\\'));
                String modelName = sep >= 0 ? full.substring(sep + 1) : full;
                String base = modelName.replaceAll("-v?\\d+[\\d.]*$", "");
                if (!base.isBlank()) result.putIfAbsent(base, full);
            }
        }
        return result;
    }
}
