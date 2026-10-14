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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Executes a replay run step-by-step against the live QuPath image.
 *
 * A single {@link GroovyShell}/{@link Binding} is shared for the whole run — variables
 * defined in one step's fragment (e.g. {@code def mask = ...}) must stay visible to the
 * next, exactly as they would in the equivalent full-script replay.
 *
 * <b>Fragments run on a dedicated background thread, not the JavaFX Application Thread.</b>
 * That mirrors QuPath's own Script Editor: {@code DefaultScriptEditor} runs "Run" on a
 * single-thread {@code ExecutorService} by default too ("Run Script in JavaFX thread" is an
 * opt-in most users never enable) — {@code QPEx} static calls don't require the FX thread for
 * this. A single-threaded pixel classifier or InstanSeg detection step can legitimately take
 * minutes; running that ON the FX thread instead blocks event dispatch for the whole duration
 * — the "Running…" overlay stops animating and the OS itself flags QuPath as "Not responding"
 * even though it's working, because the FX Application Thread never returns to the event loop.
 *
 * <b>Imports are wired through {@link CompilerConfiguration}, not as text inside the
 * evaluated fragments.</b> Each {@code GroovyShell.evaluate(String)} call compiles its
 * argument into a brand new synthetic class ({@code Script1}, {@code Script2}, ...) — the
 * shared {@link Binding} carries variables across those calls, but plain {@code import}
 * lines are lexically scoped to the string they appear in and do NOT carry over. Attaching
 * an {@link ImportCustomizer} to the shell's compiler configuration applies the same
 * imports {@code QTraceReplayEngine}'s full-script preamble uses to every fragment this
 * shell ever compiles, regardless of which call it came from.
 */
public class ReplayExecutor {

    public enum Status { OK, FAILED, SKIPPED_EXCLUDED, SKIPPED_INCOMPATIBLE }

    public record StepResult(Status status, String errorMessage, long durationMs, String stackTrace) {
        static StepResult ok(long durationMs) { return new StepResult(Status.OK, null, durationMs, null); }
        static StepResult failed(String message, long durationMs, String stackTrace) {
            return new StepResult(Status.FAILED, message, durationMs, stackTrace);
        }
    }

    private final GroovyShell shell;
    private final ExecutorService worker;
    private final ProgressAppender progressAppender;
    private final Logger rootLogger;

    /**
     * @param extensionClassLoader classloader that can resolve extension classes (InstanSeg, StarDist, ...) —
     *                             same one used for {@code ServiceLoader.load(QTracePlugin.class, ...)}.
     * @param progressSink         receives formatted lines from QuPath's own logging (e.g. InstanSeg /
     *                             CommandLineTaskRunner's "Completed Annotation (N%)" progress) while a
     *                             step runs — plugged into the Player's Console panel so long-running
     *                             segmentation isn't silent there. Called from whichever thread logged
     *                             the event; the caller is responsible for hopping to the FX thread.
     */
    public ReplayExecutor(ClassLoader extensionClassLoader, Consumer<String> progressSink) {
        ImportCustomizer imports = new ImportCustomizer();
        imports.addStaticStars("qupath.lib.gui.scripting.QPEx");
        imports.addStarImports(
            "qupath.lib.objects",
            "qupath.lib.objects.classes",
            "qupath.lib.roi",
            "qupath.lib.common",
            "qupath.lib.measurements",
            "qupath.lib.scripting",
            "qupath.lib.images.servers");
        CompilerConfiguration config = new CompilerConfiguration();
        config.addCompilationCustomizers(imports);
        this.shell = new GroovyShell(extensionClassLoader, new Binding(), config);

        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "qtrace-replay-exec");
            t.setDaemon(true);
            return t;
        });

        // Mirrors QuPath's own task/plugin progress (e.g. InstanSeg's "Completed Annotation
        // (N%)") into the Player's console — only "qupath.*" loggers, so third-party/runtime
        // libraries (ONNX, HTTP clients, ...) that also log at INFO don't flood it.
        this.rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        this.progressAppender = new ProgressAppender(progressSink);
        progressAppender.setContext(rootLogger.getLoggerContext());
        progressAppender.start();
        rootLogger.addAppender(progressAppender);
    }

    /**
     * Evaluates the run's preamble (imports, extension check, classifier restoration, ...)
     * once, before any step is executed. Must be called before the first {@link #executeStep}.
     */
    public CompletableFuture<StepResult> executePreamble(String preambleGroovy) {
        return evaluateAsync(preambleGroovy);
    }

    /**
     * Sets a variable directly on the shared {@link Binding}, visible to every fragment
     * evaluated afterward (preamble and steps alike) — used for {@code _qtraceSourceDir},
     * so an "Import objects from file" step's fragment can resolve its companion file by
     * name only, relative to the directory the source .qtrace was loaded from.
     */
    public void setVariable(String name, Object value) {
        shell.getContext().setVariable(name, value);
    }

    /** Evaluates one step's already-rewritten Groovy fragment against the shared shell/binding. */
    public CompletableFuture<StepResult> executeStep(ReplayStep step) {
        return evaluateAsync(step.groovyFragment());
    }

    /** Detaches the log appender and shuts down the background executor — call once per run, when a new run starts or the Player closes. */
    public void close() {
        rootLogger.detachAppender(progressAppender);
        progressAppender.stop();
        worker.shutdownNow();
    }

    private CompletableFuture<StepResult> evaluateAsync(String groovy) {
        CompletableFuture<StepResult> future = new CompletableFuture<>();
        worker.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                shell.evaluate(groovy);
                future.complete(StepResult.ok(System.currentTimeMillis() - start));
            } catch (Exception e) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                future.complete(StepResult.failed(e.getMessage(), System.currentTimeMillis() - start, sw.toString()));
            }
        });
        return future;
    }

    private static class ProgressAppender extends AppenderBase<ILoggingEvent> {
        private final Consumer<String> sink;

        ProgressAppender(Consumer<String> sink) { this.sink = sink; }

        @Override
        protected void append(ILoggingEvent event) {
            if (!event.getLoggerName().startsWith("qupath.")) return;
            sink.accept("[" + event.getLevel() + "] " + event.getFormattedMessage());
        }
    }
}
