package unit;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.tck.ReadContract;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the TCK's cursor walk: it finds the defects that would hang a run in the host forever, namely
 * readers that never terminate and repeated cursor values. Correct paging over several pages passes.
 */
public class TestReadContract {

    private final SyncRunContext runContext = new SyncRunContext(UUID.randomUUID(), "PRJ-1");

    private static SourcePage<ElementArtifact> page(int elements, String nextCursor) {
        List<ElementArtifact> content = java.util.stream.IntStream.range(0, elements)
                .mapToObj(index -> new ElementArtifact())
                .toList();
        return new SourcePage<>(content, List.of(), elements,
                nextCursor == null ? null : new PageCursor(nextCursor), nextCursor == null);
    }

    @Test
    public void readsAllPagesAndAccumulatesElementsAndFailures() throws Exception {
        ReadContract.PhaseResult<ElementArtifact> result = ReadContract.readAll("readArtifacts",
                (context, scope, cursor) -> {
                    if (cursor == null) {
                        return page(2, "2");
                    }
                    return page(1, null);
                }, runContext, ReadScope.full(), 10);

        assertEquals(2, result.pages());
        assertEquals(3, result.elements().size());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    public void aRepeatedCursorValueIsAnInfiniteLoop() {
        TckViolation violation = assertThrows(TckViolation.class, () ->
                ReadContract.readAll("readArtifacts",
                        (context, scope, cursor) -> page(1, "same"),
                        runContext, ReadScope.full(), 10));

        assertTrue(violation.getMessage().contains("twice"),
                "the message names the repetition: " + violation.getMessage());
    }

    @Test
    public void missingTerminationIsReportedAgainstMaxPages() {
        //Every page has a new cursor and never lastPage: the host would never see an end
        TckViolation violation = assertThrows(TckViolation.class, () ->
                ReadContract.readAll("readComments",
                        (context, scope, cursor) -> page(1,
                                String.valueOf(cursor == null ? 1 : Integer.parseInt(cursor.value()) + 1)),
                        runContext, ReadScope.full(), 5));

        assertTrue(violation.getMessage().contains("5 pages"),
                "the message names the upper limit: " + violation.getMessage());
    }

    @Test
    public void aNullPageIsAViolationWithGuidance() {
        TckViolation violation = assertThrows(TckViolation.class, () ->
                ReadContract.readAll("readRelationships",
                        (context, scope, cursor) -> null,
                        runContext, ReadScope.full(), 10));

        assertTrue(violation.getMessage().contains("SourcePage.empty()"),
                "the message names the remedy: " + violation.getMessage());
    }
}
