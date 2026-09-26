package io.qtrace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Swapping a provisional certificate for the verified one keeps the encrypted key (loader.md § 17). */
class CertificateFileTest {

    @Test
    void replacesOnlyTheJwtOfAnEnvelope() {
        String env = "{\"jwt\":\"old.jwt.x\",\"sk_enc\":\"E\",\"sk_salt\":\"S\",\"sk_iv\":\"I\"}";
        JsonObject o = JsonParser.parseString(CertificateFile.replaceJwt(env, "new.jwt.y")).getAsJsonObject();
        assertEquals("new.jwt.y", o.get("jwt").getAsString());
        assertEquals("E", o.get("sk_enc").getAsString());
        assertEquals("S", o.get("sk_salt").getAsString());
        assertEquals("I", o.get("sk_iv").getAsString());
    }

    @Test
    void aBareJwtFileBecomesTheNewJwt() {
        assertEquals("new.jwt.y", CertificateFile.replaceJwt("old.jwt.x\n", "new.jwt.y"));
    }

    @Test
    void readsTheJwtOfEitherForm() {
        assertEquals("a.b.c", CertificateFile.jwtOf("{\"jwt\":\"a.b.c\",\"sk_enc\":\"E\"}"));
        assertEquals("a.b.c", CertificateFile.jwtOf(" a.b.c \n"));
        assertNull(CertificateFile.jwtOf("{\"sk_enc\":\"E\"}"));
    }
}
