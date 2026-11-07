package io.qtrace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the capture compares in the script fragments it records for manual annotations. */
final class AnnotationFragments {
    private AnnotationFragments() {}

    private static final Pattern PAYLOAD = Pattern.compile("decode\\(\"([A-Za-z0-9+/=]+)\"\\)");

    /**
     * The fragment with the annotation's lock flag left out of its payload. QuPath locks an annotation once a plugin
     * (a cell detection) has run on it, with nobody having edited it, and the replay locks it again by running the
     * same plugin: a lock alone is not a change to record, whenever the capture is refreshed (a Stamp does).
     */
    static String withoutLock(String fragment) {
        if (fragment == null) return null;
        Matcher m = PAYLOAD.matcher(fragment);
        if (!m.find()) return fragment;
        try {
            JsonObject json = JsonParser.parseString(
                new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("properties") && json.get("properties").isJsonObject())
                json.getAsJsonObject("properties").remove("isLocked");
            return fragment.substring(0, m.start(1))
                + Base64.getEncoder().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8))
                + fragment.substring(m.end(1));
        } catch (RuntimeException e) {
            return fragment;
        }
    }
}
