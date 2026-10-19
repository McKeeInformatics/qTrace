package io.qtrace.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every qTrace request goes through {@link QTraceHttp}: a client built elsewhere would ignore the
 * proxy and the institution certificate of Settings › Network, and fail in a hospital. Scans the
 * main sources of Core and its modules (the loader, which never updates itself, is the exception;
 * so is this package, and the connection test's direct read of a PAC script).
 */
class OneHttpClientTest {

    private static final List<String> ALLOWED = List.of(
        "/qtrace-loader/", "/io/qtrace/net/");

    @Test
    void noModuleBuildsItsOwnHttpClient() throws IOException {
        Path repo = Path.of("").toAbsolutePath().getParent();
        List<Path> roots = Stream.concat(Stream.of(Path.of("").toAbsolutePath()),
                repo == null ? Stream.empty() : siblings(repo)).toList();
        List<String> offenders = roots.stream().flatMap(OneHttpClientTest::javaFiles)
                .filter(p -> p.toString().contains("/src/main/java/"))
                .filter(p -> ALLOWED.stream().noneMatch(a -> p.toString().replace('\\', '/').contains(a)))
                .filter(OneHttpClientTest::buildsAClient)
                .map(Path::toString).distinct().sorted().toList();
        assertEquals(List.of(), offenders, "use QTraceHttp.newBuilder()");
    }

    private static Stream<Path> siblings(Path repo) {
        try {
            return Files.list(repo).filter(p -> p.getFileName().toString().startsWith("qtrace-")
                && !p.getFileName().toString().equals("qtrace-core")).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static Stream<Path> javaFiles(Path root) {
        try {
            return Files.walk(root).filter(p -> p.toString().endsWith(".java")
                && !p.toString().contains("/build/")).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static boolean buildsAClient(Path p) {
        try {
            String s = Files.readString(p);
            return s.contains("HttpClient.newBuilder(") || s.contains("HttpClient.newHttpClient(")
                || s.contains("openConnection(") || s.contains(".openStream(");
        } catch (IOException e) {
            return false;
        }
    }
}
