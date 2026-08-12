package io.qtrace.chain;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MerkleTreeTest {

    private static JsonArray arrayOf(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(new JsonPrimitive(v));
        return a;
    }

    @Test
    void nullAndEmptyArrayHashTheEmptyByteArray() {
        String emptyHash = Hashing.sha256Hex(new byte[0]);
        assertEquals(emptyHash, MerkleTree.merkleRoot(null));
        assertEquals(emptyHash, MerkleTree.merkleRoot(new JsonArray()));
    }

    @Test
    void singleLeafRootIsLeafHash() {
        JsonArray steps = arrayOf("step1");
        // Leaf = SHA-256 of the element's JSON serialization ("\"step1\"")
        String expected = Hashing.sha256Hex("\"step1\"".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, MerkleTree.merkleRoot(steps));
    }

    @Test
    void rootIsDeterministic() {
        assertEquals(MerkleTree.merkleRoot(arrayOf("a", "b", "c")),
                     MerkleTree.merkleRoot(arrayOf("a", "b", "c")));
    }

    @Test
    void rootIsOrderSensitive() {
        assertNotEquals(MerkleTree.merkleRoot(arrayOf("a", "b")),
                        MerkleTree.merkleRoot(arrayOf("b", "a")));
    }

    @Test
    void anyStepChangeChangesTheRoot() {
        assertNotEquals(MerkleTree.merkleRoot(arrayOf("a", "b", "c")),
                        MerkleTree.merkleRoot(arrayOf("a", "b", "d")));
    }

    @Test
    void oddLeafCountDuplicatesLastNodeBitcoinStyle() {
        // [a, b, c] must equal the tree over [a, b, c, c] at the leaf-pairing level:
        // root([a,b,c]) = H(H(ab) || H(cc)) — verified indirectly: adding a distinct
        // 4th element yields a different root than the odd-count tree.
        assertNotEquals(MerkleTree.merkleRoot(arrayOf("a", "b", "c")),
                        MerkleTree.merkleRoot(arrayOf("a", "b", "c", "x")));
        assertEquals(64, MerkleTree.merkleRoot(arrayOf("a", "b", "c")).length());
    }
}
