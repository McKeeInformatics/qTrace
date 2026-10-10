package io.qtrace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A stamp that produces no certificate is a basic record: qtb_ id and self-declared identity, beside the stamp. */
class BasicRecordTest {

    @Test
    void anIdIsQtbPlusAnUlid() {
        String id = BasicRecord.newId();
        assertTrue(id.matches("^qtb_[0-9A-HJKMNP-TV-Z]{26}$"), id);
        assertNotEquals(id, BasicRecord.newId());
        assertTrue(BasicRecord.isId(id));
    }

    @Test
    void onlyQtbPlusUlidIsABasicId() {
        assertFalse(BasicRecord.isId(null));
        assertFalse(BasicRecord.isId("qtc_01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        assertFalse(BasicRecord.isId("qtb_short"));
        assertFalse(BasicRecord.isId("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAI"), "I is not Crockford");
    }

    @Test
    void aStampWithoutCertificateIsBasic() {
        assertTrue(BasicRecord.applies(true, false));
        assertFalse(BasicRecord.applies(true, true), "its id is the .qtcert's");
        assertFalse(BasicRecord.applies(false, false), "an unstamped session is not a record");
        assertFalse(BasicRecord.applies(false, true));
    }

    @Test
    void theBlockSitsBesideTheStampWithTheIdentityNature() {
        JsonObject b = BasicRecord.block("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV");
        assertEquals("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV", b.get("id").getAsString());
        assertEquals("self_declared", b.get("identity").getAsString());
        assertEquals(2, b.size());
    }

    @Test
    void readsTheIdOfTheLatestStamp() {
        JsonObject root = JsonParser.parseString("""
            {"sessions":[
              {"session_id":"a","validation":{"validator":"x"},"record":{"id":"qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV","identity":"self_declared"}},
              {"session_id":"b","validation":{"validator":"x"},"record":{"id":"qtb_01BX5ZZKBKACTAV9WEVGEMMVRZ","identity":"self_declared"}}]}""").getAsJsonObject();
        assertEquals("qtb_01BX5ZZKBKACTAV9WEVGEMMVRZ", BasicRecord.idOfLatestStamp(root));
    }

    @Test
    void anAutosavedSessionAfterTheStampDoesNotHideIt() {
        JsonObject root = JsonParser.parseString("""
            {"sessions":[
              {"session_id":"a","validation":{"validator":"x"},"record":{"id":"qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV","identity":"self_declared"}},
              {"session_id":"b","validation":null,"validation_state":"unstamped"}]}""").getAsJsonObject();
        assertEquals("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV", BasicRecord.idOfLatestStamp(root));
    }

    @Test
    void aLaterCertifiedStampSupersedesAnEarlierBasicOne() {
        JsonObject root = JsonParser.parseString("""
            {"sessions":[
              {"session_id":"a","validation":{"validator":"x"},"record":{"id":"qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV","identity":"self_declared"}},
              {"session_id":"b","validation":{"validator":"x"}}]}""").getAsJsonObject();
        assertNull(BasicRecord.idOfLatestStamp(root));
    }

    @Test
    void noIdWithoutAStampOrWithAForeignId() {
        assertNull(BasicRecord.idOfLatestStamp(JsonParser.parseString("{\"sessions\":[]}").getAsJsonObject()));
        assertNull(BasicRecord.idOfLatestStamp(new JsonObject()));
        assertNull(BasicRecord.idOfLatestStamp(null));
        assertNull(BasicRecord.idOfLatestStamp(JsonParser.parseString(
            "{\"sessions\":[{\"validation\":{},\"record\":{\"id\":\"qtc_whatever\"}}]}").getAsJsonObject()));
        assertNull(BasicRecord.idOf(null));
    }

    // ── Re-stamping: a certified stamp over a basic one declares the record it certifies ──

    private static final String BASIC_LAST = """
        {"sessions":[
          {"session_id":"a","validation":{"validator":"x"},"record":{"id":"qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV","identity":"self_declared"}},
          {"session_id":"b","validation":null,"validation_state":"unstamped"}]}""";

    @Test
    void aCertifiedStampOverABasicOneDeclaresIt() {
        JsonObject root = JsonParser.parseString(BASIC_LAST).getAsJsonObject();
        assertEquals("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV", BasicRecord.restampedFrom(root, true));
    }

    @Test
    void aBasicStampNeverDeclaresAnything() {
        JsonObject root = JsonParser.parseString(BASIC_LAST).getAsJsonObject();
        assertNull(BasicRecord.restampedFrom(root, false));
    }

    @Test
    void aCertifiedStampOverACertifiedOrNoStampDeclaresNothing() {
        JsonObject certified = JsonParser.parseString("""
            {"sessions":[{"session_id":"a","validation":{"validator":"x"}}]}""").getAsJsonObject();
        assertNull(BasicRecord.restampedFrom(certified, true));
        assertNull(BasicRecord.restampedFrom(JsonParser.parseString("{\"sessions\":[]}").getAsJsonObject(), true));
        assertNull(BasicRecord.restampedFrom(null, true));
    }

    @Test
    void theRestampedFromBlockCarriesTheIdAndIsReadBack() {
        JsonObject block = BasicRecord.restampedFromBlock("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV");
        assertEquals("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV", block.get("id").getAsString());
        assertEquals(1, block.size());
        JsonObject session = new JsonObject();
        session.add("restamped_from", block);
        assertEquals("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV", BasicRecord.restampedFromOf(session));
        assertNull(BasicRecord.restampedFromOf(new JsonObject()));
        assertNull(BasicRecord.restampedFromOf(null));
    }

    @Test
    void theLatestStampDeclaresItsOriginForThePush() {
        JsonObject root = JsonParser.parseString("""
            {"sessions":[
              {"session_id":"a","validation":{"validator":"x"},"record":{"id":"qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV"}},
              {"session_id":"b","validation":{"validator":"x"},"restamped_from":{"id":"qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV"}},
              {"session_id":"c","validation":null,"validation_state":"unstamped"}]}""").getAsJsonObject();
        assertEquals("qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV", BasicRecord.restampedFromOfLatestStamp(root));
        assertNull(BasicRecord.restampedFromOfLatestStamp(JsonParser.parseString(BASIC_LAST).getAsJsonObject()));
    }
}
