package io.qtrace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Sets a .qtrace aside at a Full reset: the file (and its {@code .files} folder) is renamed {@code <name>.qtrace.reset-<date>},
 * never deleted and never overwritten. The new name no longer ends in {@code .qtrace}, so no
 * reader (Dashboard, Library, player) takes it for a record, and the next stamp starts a fresh chain.
 */
final class QTraceArchive {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private QTraceArchive() {}

    /** @return the archived file, or empty when there was no .qtrace to set aside */
    static Optional<Path> archive(Path qtraceFile) throws IOException {
        if (qtraceFile == null || !Files.isRegularFile(qtraceFile)) return Optional.empty();
        String base = qtraceFile.getFileName() + ".reset-" + LocalDateTime.now().format(STAMP);
        Path target = qtraceFile.resolveSibling(base);
        for (int n = 2; Files.exists(target); n++) target = qtraceFile.resolveSibling(base + "-" + n);
        Path moved = Files.move(qtraceFile, target);
        // The files beside the record (classifiers...) go with it: left behind, the next record would pick them up.
        Path companions = qtraceFile.resolveSibling(qtraceFile.getFileName() + ".files");
        if (Files.isDirectory(companions))
            Files.move(companions, moved.resolveSibling(moved.getFileName() + ".files"));
        return Optional.of(moved);
    }
}
