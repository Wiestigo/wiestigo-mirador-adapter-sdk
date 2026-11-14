package unit_tests;

import com.rmm.adapter.api.ElementFailure;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.SourcePage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The invariants of the result page: the end of paging and the counts are consistent. */
class TestSourcePage {

    @Test
    void cursorAndLastPageExcludeEachOther() {
        //last page with a cursor: contradictory
        assertThrows(IllegalArgumentException.class,
                () -> new SourcePage<>(List.of(), List.of(), 0, new PageCursor("7"), true));
        //intermediate page without a cursor: the host could not continue
        assertThrows(IllegalArgumentException.class,
                () -> new SourcePage<>(List.of(), List.of(), 0, null, false));
    }

    @Test
    void readCountAlsoCountsFailedElements() {
        //2 delivered, 1 converted: readCount < elements.size() would be a miscount
        assertThrows(IllegalArgumentException.class,
                () -> new SourcePage<>(List.of("a", "b"), List.of(), 1, null, true));

        SourcePage<String> page = new SourcePage<>(List.of("a"),
                List.of(ElementFailure.of("ext-2", "not convertible")), 2, null, true);
        assertEquals(2, page.readCount());
        assertEquals(1, page.elements().size());
        assertEquals(1, page.failures().size());
    }

    @Test
    void emptyLastPageForToolsWithoutRelationshipsOrComments() {
        SourcePage<String> page = SourcePage.empty();
        assertTrue(page.lastPage());
        assertEquals(0, page.readCount());
        assertTrue(page.elements().isEmpty());
    }
}
