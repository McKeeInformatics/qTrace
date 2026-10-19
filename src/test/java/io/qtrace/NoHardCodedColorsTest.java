package io.qtrace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The palette lives in {@link Theme}: a literal colour anywhere else would stay dark in light mode. */
class NoHardCodedColorsTest {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}\\b");
    // loader: runs before Core exists, cannot use Theme
    private static final List<String> EXEMPT = List.of("Theme.java", "/qtrace-loader/");

    @Test
    void everyColourComesFromTheme() throws IOException {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(repo.resolve("qtrace-compliance")), "not inside the full repository");
        List<String> hits;
        try (Stream<Path> files = Files.walk(repo)) {
            hits = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> repo.relativize(p).startsWith("qtrace-")) // not the unbuilt legacy src/ at the root
                .filter(p -> p.toString().contains("/src/main/java/"))
                .filter(p -> EXEMPT.stream().noneMatch(e -> p.toString().contains(e)))
                .flatMap(p -> {
                    try {
                        return Files.readAllLines(p).stream().filter(l -> HEX.matcher(l).find())
                            .map(l -> repo.relativize(p) + ": " + l.trim());
                    } catch (IOException e) { throw new RuntimeException(e); }
                })
                .collect(Collectors.toList());
        }
        assertTrue(hits.isEmpty(), "Use Theme tokens, not literals:\n" + String.join("\n", hits));
    }
}
