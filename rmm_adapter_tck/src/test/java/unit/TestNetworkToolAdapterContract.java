package unit;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.FakeHttpTool;
import com.rmm.adapter.tck.ReadContract;
import com.rmm.adapter.tck.TckAdapterContext;
import com.rmm.adapter.tck.reference.NetworkTool;
import com.rmm.adapter.tck.reference.NetworkToolAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every contract case against the network reference adapter ({@link NetworkToolAdapter}) on
 * {@link TckAdapterContext}. Case 2 runs with two spaces and two tokens, case 4 with {@code executor()},
 * {@code caches()} and {@code runStore()}, the pause with an offset as cursor, and case 7 with the removal
 * of the adapter's own webhook.
 */
public class TestNetworkToolAdapterContract extends AdapterContractTest {

    private NetworkTool tool;

    @BeforeEach
    public void startTheTool() throws IOException {
        tool = NetworkTool.start();
    }

    @AfterEach
    public void stopTheTool() {
        tool.close();
    }

    @Override
    protected ToolAdapter adapter() {
        return new NetworkToolAdapter();
    }

    @Override
    protected Map<String, String> configuration() {
        return Map.of("tool.url", tool.address(NetworkTool.FIRST).toString(),
                "tool.token", tool.token(NetworkTool.FIRST));
    }

    @Override
    protected Optional<Map<String, String>> secondBindingConfiguration() {
        return Optional.of(Map.of("tool.url", tool.address(NetworkTool.SECOND).toString(),
                "tool.token", tool.token(NetworkTool.SECOND)));
    }

    @Override
    protected Optional<FakeHttpTool> networkTool() {
        return Optional.of(tool.http());
    }

    @Override
    protected Optional<SyncRunContext> liveRunContext() {
        return Optional.of(new SyncRunContext(UUID.randomUUID(), "NET-PRJ"));
    }

    /** The tool keeps no state between two requests that could expire; the cursor is an offset. */
    @Override
    protected void expireToolState() {
        //nothing expires
    }

    /** Case 7: {@code purge()} removes the connection's webhook and leaves the rows in place. */
    @Test
    public void purgeRemovesOnlyTheOwnWebhook() throws Exception {
        TckAdapterContext context = TckAdapterContext.of(adapter().descriptor(), configuration());
        var instance = adapter().bind(context);
        try {
            instance.purge();
        } finally {
            instance.close();
            context.release();
        }

        assertEquals(Set.of(NetworkTool.FIRST + "/" + context.binding().bindingId()), tool.removedHooks());
        assertEquals(List.of("F-1", "F-2", "F-3", "F-4", "F-5"), tool.rows(NetworkTool.FIRST));
    }

    /**
     * The pages go through {@code executor()}; the labels are loaded once per connection and then come from
     * {@code caches()}, unchanged even when they contain quotation marks and backslashes.
     */
    @Test
    public void pagesGoThroughTheExecutorAndLabelsThroughTheCaches() throws Exception {
        TckAdapterContext context = TckAdapterContext.of(adapter().descriptor(), configuration());
        var instance = adapter().bind(context);
        try {
            for (int run = 0; run < 2; run++) {
                SyncRunContext reading = new SyncRunContext(UUID.randomUUID(), "NET-PRJ");
                var result = context.inRun(reading, () -> ReadContract.readAll("readArtifacts",
                        instance.reader().orElseThrow()::readArtifacts, reading, ReadScope.full(), 10));
                assertEquals(5, result.elements().size(), "each run delivers every row once");
                for (ElementArtifact artifact : result.elements()) {
                    assertEquals(NetworkTool.label(artifact.getIdValue()),
                            artifact.getFieldByName("label").getValueAsString());
                }
            }
        } finally {
            instance.close();
            context.release();
        }

        long labels = tool.http().requests().stream().filter(request -> request.path().endsWith("/labels")).count();
        assertEquals(1, labels, "the labels are loaded once and then read from caches()");
        assertTrue(tool.http().requests().stream().allMatch(request -> request.stamp() != null));
    }

    /**
     * The reader filters nothing: when the same run reads a page twice, its rows arrive twice.
     * {@code runStore()} only records what was read.
     */
    @Test
    public void readingTheSamePageTwiceInARunDeliversItsRowsTwice() throws Exception {
        TckAdapterContext context = TckAdapterContext.of(adapter().descriptor(), configuration());
        var instance = adapter().bind(context);
        try {
            SyncRunContext reading = new SyncRunContext(UUID.randomUUID(), "NET-PRJ");
            List<Integer> sizes = context.inRun(reading, () -> {
                var reader = instance.reader().orElseThrow();
                int first = reader.readArtifacts(reading, ReadScope.full(), null).elements().size();
                int again = reader.readArtifacts(reading, ReadScope.full(), null).elements().size();
                return List.of(first, again);
            });
            assertEquals(List.of(2, 2), sizes);
        } finally {
            instance.close();
            context.release();
        }
    }
}
