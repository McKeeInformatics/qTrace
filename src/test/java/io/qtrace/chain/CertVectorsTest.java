package io.qtrace.chain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared certificate vectors (tools/cert-vectors/): on every file, the same detected format
 * and the same verdict as the portal's verifier (web/lib/qtrace-signature.ts) and qtrace-verify;
 * and CanonicalJson against the RFC 8785 reference library's flat-map vectors.
 */
class CertVectorsTest {

    private static final Path VECTORS = Path.of("..", "tools", "cert-vectors");

    private static JsonObject read(String rel) throws Exception {
        return JsonParser.parseString(Files.readString(VECTORS.resolve(rel))).getAsJsonObject();
    }

    private static List<JsonObject> cases() throws Exception {
        return StreamSupport.stream(read("cases.json").getAsJsonArray("cases").spliterator(), false)
            .map(JsonElement::getAsJsonObject).toList();
    }

    private static String formatName(CertificateCheck.Format f) {
        return switch (f) {
            case V1_0 -> "1.0";
            case V1_1 -> "1.1";
            case LEGACY_1 -> "legacy/1";
            case UNSUPPORTED -> "unsupported";
        };
    }

    @TestFactory
    List<DynamicTest> sameFormatAndVerdictAsEveryVerifier() throws Exception {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.get("file").getAsString(), () -> {
            JsonObject doc = read(c.get("file").getAsString());
            assertEquals(c.get("format").getAsString(), formatName(CertificateCheck.format(doc)));
            assertEquals(c.get("verdict").getAsString(), CertificateCheck.verify(doc).name().toLowerCase(),
                c.get("why").getAsString());
        })).toList();
    }

    @Test
    void everyFormatAndVerdictIsCovered() throws Exception {
        Set<String> formats = new HashSet<>(), verdicts = new HashSet<>();
        for (JsonObject c : cases()) {
            formats.add(c.get("format").getAsString());
            verdicts.add(c.get("verdict").getAsString());
        }
        assertEquals(Set.of("1.0", "1.1", "legacy/1", "unsupported"), formats);
        assertEquals(Set.of("valid", "invalid", "unsupported"), verdicts);
    }

    @TestFactory
    List<DynamicTest> canonicalJsonIsRfc8785OnFlatMaps() throws Exception {
        return StreamSupport.stream(read("jcs/flat-maps.json").getAsJsonArray("vectors").spliterator(), false)
            .map(JsonElement::getAsJsonObject)
            .map(v -> DynamicTest.dynamicTest(v.get("name").getAsString(), () -> {
                Map<String, String> input = new LinkedHashMap<>();
                for (var e : v.getAsJsonObject("input").entrySet())
                    input.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
                assertEquals(v.get("canonical").getAsString(), CanonicalJson.of(input));
            })).toList();
    }
}
