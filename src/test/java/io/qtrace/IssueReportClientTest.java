package io.qtrace;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IssueReportClientTest {

    @Test
    void bodyCarriesTypeImagesAndEnvButNoReporter() {
        JsonObject b = IssueReportClient.buildBody("T", "D", true,
            List.of(new byte[] {1, 2, 3}), "1.1.5", "0.7.0", "Linux");
        assertEquals("bug", b.get("type").getAsString());
        assertEquals("AQID", b.getAsJsonArray("images").get(0).getAsString());
        assertEquals("1.1.5", b.getAsJsonObject("env").get("appVersion").getAsString());
        assertFalse(b.has("reportedBy"), "reporter identity must come from the license, not the client");
    }

    @Test
    void featureTypeAndOmittedEnv() {
        JsonObject b = IssueReportClient.buildBody("T", "D", false, List.of(), null, null, null);
        assertEquals("feature", b.get("type").getAsString());
        assertEquals(0, b.getAsJsonArray("images").size());
        assertEquals(0, b.getAsJsonObject("env").size());
    }

    @Test
    void statusMapsToUserMessages() {
        assertEquals("report.sent", IssueReportClient.messageKey(200));
        assertEquals("report.err.license", IssueReportClient.messageKey(401));
        assertEquals("report.err.license", IssueReportClient.messageKey(403));
        assertEquals("report.err.rate", IssueReportClient.messageKey(429));
        assertEquals("report.err.server", IssueReportClient.messageKey(0));
        assertEquals("report.err.server", IssueReportClient.messageKey(502));
    }

    @Test
    void resultOkRequiresIssueNumber() {
        assertTrue(new IssueReportClient.Result(200, 12, null).ok());
        assertFalse(new IssueReportClient.Result(200, 0, null).ok());
        assertFalse(new IssueReportClient.Result(429, 0, "x").ok());
    }
}
