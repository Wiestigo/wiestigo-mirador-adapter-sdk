package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterStoreLimitException;
import com.rmm.adapter.api.DocumentCollection;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.tck.TckAdapterContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the TCK's document stores follow the rules of {@link DocumentCollection}: typed equality,
 * pages in the order of the UTF-8 bytes, reserved names and the platform limits. An adapter gets the same
 * answers as in the host.
 */
public class TestTckDocumentCollections {

    private static TckAdapterContext context() {
        return TckAdapterContext.of(new AdapterDescriptor("doctool", "Documents", "1.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                        false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null),
                List.of(), List.of()), Map.of());
    }

    private static List<String> ids(Stream<DocumentCollection.Document> documents) {
        try (documents) {
            return documents.map(DocumentCollection.Document::id).toList();
        }
    }

    @Test
    public void equalityIsTypedAndListsObjectsAndNullNeverMatch() {
        DocumentCollection items = context().store().collection("items");
        items.index("n");
        items.put("integer", "{\"n\":5}");
        items.put("decimal", "{\"n\":5.0}");
        items.put("exponent", "{\"n\":0.5e1}");
        items.put("text", "{\"n\":\"5\"}");
        items.put("list", "{\"n\":[5]}");
        items.put("object", "{\"n\":{\"v\":5}}");
        items.put("null", "{\"n\":null}");
        items.put("missing", "{}");
        items.put("true", "{\"n\":true}");

        assertEquals(List.of("decimal", "exponent", "integer"), ids(items.find("n", 5L)));
        assertEquals(List.of("text"), ids(items.find("n", "5")));
        assertEquals(List.of("true"), ids(items.find("n", true)));
        assertEquals(List.of(), ids(items.find("n", false)));
        assertThrows(IllegalArgumentException.class, () -> items.find("m", 5L), "only declared fields");
    }

    @Test
    public void allPagesInTheOrderOfTheUtf8BytesOfTheId() {
        DocumentCollection items = context().caches().collection("pages");
        //In UTF-16 the emoji (D83D ...) sorts before U+FFFF, in UTF-8 after it
        for (String id : List.of("b", "\uD83D\uDE00", "a", "\uFFFF", "B")) {
            items.put(id, "{}");
        }

        assertEquals(List.of("B", "a", "b", "\uFFFF", "\uD83D\uDE00"), ids(items.all(null, 10)));
        assertEquals(List.of("\uFFFF"), ids(items.all("b", 1)));
        assertEquals(List.of(), ids(items.all("\uD83D\uDE00", 10)));
        assertThrows(IllegalArgumentException.class, () -> items.all(null, 0));
        assertThrows(IllegalArgumentException.class, () -> items.all(null, DocumentCollection.MAX_PAGE + 1));
    }

    @Test
    public void onlyJsonObjectsWithoutReservedNamesAreStored() {
        DocumentCollection items = context().store().collection("items");

        assertThrows(IllegalArgumentException.class, () -> items.put("a", "[1]"));
        assertThrows(IllegalArgumentException.class, () -> items.put("a", "{\"_id\":1}"));
        assertThrows(IllegalArgumentException.class, () -> items.put("a", "{\"x\":{\"$set\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> items.put("a", "{\"a.b\":1}"));
        assertThrows(IllegalArgumentException.class, () -> items.put("a", "{\"a\":01}"));
        assertThrows(IllegalArgumentException.class, () -> items.put("", "{}"));
        assertThrows(IllegalArgumentException.class, () -> items.put("x".repeat(513), "{}"));
        assertThrows(IllegalArgumentException.class, () -> context().store().collection("Upper_Case"));
        assertThrows(IllegalArgumentException.class, () -> items.put("a", "{\"a\\u0000b\":1}"),
                "BSON refuses NUL in a name");
        assertThrows(IllegalArgumentException.class, () -> items.put("a", "{\"a\":123456789012345678901234}"),
                "BSON has no integer beyond 64 bits");
        items.put("a", "{\"inner\":{\"_id\":1}, \"text\":\"\\u00e4\\u0000\"}");
        assertEquals("{\"inner\": {\"_id\": 1}, \"text\": \"\u00e4\\u0000\"}", items.get("a").orElseThrow());
        assertTrue(items.delete("a"));
        assertFalse(items.delete("a"));
    }

    /**
     * A read returns what MongoDB would return: JSON written anew from BSON, not the text that was put.
     * Whitespace disappears and a number comes back by its type ({@code 1e2} as {@code 100.0}, an integer
     * beyond 32 bits as an integer); the content stays the same.
     */
    @Test
    public void aDocumentComesBackAsTheStoreWritesItNotAsTheTextThatWasPut() {
        DocumentCollection items = context().store().collection("items");
        items.index("n");
        items.put("number", "{ \"n\" :1e2,\"large\":12345678901,\"small\":-0 , \"list\":[ 1.50, true,null ] }");

        String read = items.get("number").orElseThrow();
        assertEquals("{\"n\": 100.0, \"large\": 12345678901, \"small\": 0, \"list\": [1.5, true, null]}", read);
        assertEquals(List.of(read), items.find("n", 100L).map(DocumentCollection.Document::json).toList(),
                "find and all hand out the same form");
        assertEquals(List.of(read), items.all(null, 10).map(DocumentCollection.Document::json).toList());
    }

    /**
     * The size counts in BSON, as in MongoDB. A list of 500,000 integers is about 3.5 MB of JSON, below the
     * limit, but BSON adds a type byte and the index as a name per element, which makes almost 6 MB: above
     * the limit.
     */
    @Test
    public void theSizeOfADocumentCountsInBsonNotInJson() {
        TckAdapterContext context = context();
        StringBuilder numbers = new StringBuilder("{\"n\":[");
        for (int element = 0; element < 500_000; element++) {
            numbers.append(element == 0 ? "" : ",").append(100_000 + element % 900_000);
        }
        String json = numbers.append("]}").toString();
        int utf8 = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(utf8 > 3_400_000 && utf8 < 4 * 1024 * 1024, "about 3.5 MB of JSON: " + utf8);

        AdapterStoreLimitException refused = assertThrows(AdapterStoreLimitException.class,
                () -> context.store().collection("big").put("a", json));
        assertEquals(AdapterStoreLimitException.Limit.DOCUMENT_SIZE, refused.limit());
        assertTrue(refused.getMessage().contains("in BSON"), refused.getMessage());
        context.store().collection("big").put("b", "{\"n\":[" + "1,".repeat(1000) + "1]}");
        assertTrue(context.store().collection("big").get("b").isPresent(), "a small list is stored");
    }

    @Test
    public void atTheLimitTheStoresRefuseAndTheCachesOnlyStopSaving() {
        TckAdapterContext context = context();
        String huge = "{\"x\":\"" + "a".repeat(4 * 1024 * 1024) + "\"}";

        AdapterStoreLimitException refused = assertThrows(AdapterStoreLimitException.class,
                () -> context.store().collection("big").put("a", huge));
        assertEquals(AdapterStoreLimitException.Limit.DOCUMENT_SIZE, refused.limit());
        assertTrue(refused.getMessage().startsWith(AdapterStoreLimitException.MESSAGE), refused.getMessage());
        context.caches().collection("big").put("a", huge);
        assertTrue(context.caches().collection("big").get("a").isEmpty(), "the cache did not save it");
        assertEquals(1, context.cacheHints().size(), "a hint at the connection instead of an error");
    }

    /**
     * As in the host, the run store reserves {@code runId} at the top level (in a document and in
     * {@code index}). The size counts {@code runId} but takes the id without the run in front of it: a
     * document exactly at the limit is stored, one byte more is refused.
     */
    @Test
    public void theRunStoreReservesRunIdAndCountsTheSizeWithThePlainId() throws Exception {
        TckAdapterContext context = context();
        com.rmm.adapter.api.SyncRunContext run = new com.rmm.adapter.api.SyncRunContext(java.util.UUID.randomUUID(),
                "PRJ");
        //BSON of {_id: "a", runId: <36 characters>, x: "..."}: 4 + (1+4+4+1+1) + (1+6+4+36+1) + (1+2+4+n+1) + 1
        int overhead = 4 + (1 + 4 + 4 + 1 + 1) + (1 + 6 + 4 + 36 + 1) + (1 + 2 + 4 + 1) + 1;
        String exact = "a".repeat(4 * 1024 * 1024 - overhead);

        context.inRun(run, () -> {
            DocumentCollection links = context.runStore().collection("links");
            assertThrows(IllegalArgumentException.class, () -> links.put("a", "{\"runId\":\"other\"}"),
                    "the host keeps the run there");
            assertThrows(IllegalArgumentException.class, () -> links.index("runId"));
            links.put("b", "{\"inner\":{\"runId\":1}}");
            links.put("a", "{\"x\":\"" + exact + "\"}");
            AdapterStoreLimitException refused = assertThrows(AdapterStoreLimitException.class,
                    () -> links.put("a", "{\"x\":\"" + exact + "a\"}"));
            assertEquals(AdapterStoreLimitException.Limit.DOCUMENT_SIZE, refused.limit());
            assertTrue(refused.getMessage().contains((4 * 1024 * 1024 + 1) + " bytes"), refused.getMessage());
            return null;
        });
        context.store().collection("items").put("a", "{\"runId\":\"mine\"}");
        context.store().collection("items").index("runId");
    }

    /** The TCK refuses a number beyond the range of a double ({@code 1e400}), as the host does. */
    @Test
    public void aNumberBeyondDoubleIsRefusedLikeInTheHost() {
        DocumentCollection items = context().store().collection("items");

        assertThrows(IllegalArgumentException.class, () -> items.put("a", "{\"n\":1e400}"));
        assertThrows(IllegalArgumentException.class, () -> items.put("a", "{\"n\":[{\"m\":-1e400}]}"));
        items.put("a", "{\"n\":1e300, \"small\":1e-400}");
        //Compare the values, not their notation: the driver's JSON writer prints "1.0E+300" or "1.0E300" depending on its version
        org.bson.Document stored = org.bson.Document.parse(items.get("a").orElseThrow());
        assertEquals(1e300, stored.getDouble("n"));
        assertEquals(0.0, stored.getDouble("small"));
    }

    /**
     * As in the host, a collection has at most 16 indexed fields and a store at most 64 collections; a
     * cleared cache frees its names.
     */
    @Test
    public void indexedFieldsAndCollectionsAreCappedLikeInTheHost() {
        TckAdapterContext context = context();
        DocumentCollection items = context.store().collection("items");
        for (int index = 0; index < 16; index++) {
            items.index("f" + index);
        }
        items.index("f0");
        assertThrows(IllegalArgumentException.class, () -> items.index("g"), "at most 16 per collection");

        for (int index = 0; index < 64; index++) {
            context.store().collection("c" + index).put("a", "{}");
            context.caches().collection("c" + index).put("a", "{}");
        }
        AdapterStoreLimitException refused = assertThrows(AdapterStoreLimitException.class,
                () -> context.store().collection("one_more").put("a", "{}"));
        context.store().collection("c5").put("b", "{}");
        context.caches().collection("one_more").put("a", "{}");

        assertTrue(refused.getMessage().contains("at most 64 collections in the store"), refused.getMessage());
        assertEquals(1, context.cacheHints().size(), context.cacheHints().toString());
        context.clearCaches();
        context.caches().collection("one_more").put("a", "{}");
        assertTrue(context.caches().collection("one_more").get("a").isPresent(), "freed by clearing the caches");
    }
}
