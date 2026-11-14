package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.ElementFailure;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncRunContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A read of the artifacts that stands still once, after its first page,
 * while the state on the tool's side expires: the cursor of the page before
 * must still lead on.
 */
final class PausedRead {

    private PausedRead() {
    }

    /**
     * Reads all artifacts and pauses once after the first page; returns the
     * lines sorted.
     *
     * @param expireToolState lets the state on the tool's side expire, at the start of the pause
     * @param pause           how long the pause lasts after that
     */
    static List<String> read(SourceReader reader, SyncRunContext run, IntSupplier maxPages,
                             Runnable expireToolState, Supplier<Duration> pause)
            throws AdapterException, InterruptedException {
        List<ElementArtifact> elements = new ArrayList<>();
        List<ElementFailure> failures = new ArrayList<>();
        PageCursor cursor = null;
        boolean paused = false;
        for (int pages = 1; ; pages++) {
            SourcePage<ElementArtifact> page;
            try {
                page = reader.readArtifacts(run, ReadScope.full(), cursor);
            } catch (AdapterException failed) {
                if (!paused) {
                    throw failed;
                }
                throw new TckViolation("After a long pause between two pages readArtifacts(...) failed with the"
                        + " cursor of the page before: " + failed.getMessage() + " — the state on the tool's side"
                        + " (a search session, a result set) expired meanwhile; a cursor carries what the next page"
                        + " needs (an offset, the last key, the query), not only a server-side id");
            }
            if (page == null) {
                throw new TckViolation("readArtifacts: the reader returned null instead of a SourcePage");
            }
            elements.addAll(page.elements());
            failures.addAll(page.failures());
            if (page.lastPage()) {
                //Without a second page there is no pause in between: skipped, not passed
                assumeTrue(paused, "Pause between two pages skipped: the read has only one page —"
                        + " a smaller page size in configuration() checks the cursor");
                return signaturesOf(elements, failures);
            }
            if (pages >= maxPages.getAsInt()) {
                throw new TckViolation("readArtifacts: no lastPage after " + maxPages.getAsInt() + " pages");
            }
            cursor = page.nextCursor();
            if (!paused) {
                paused = true;
                expireToolState.run();
                Thread.sleep(pause.get().toMillis());
            }
        }
    }

    /** The elements and element failures of a read as sorted lines, comparable across two reads. */
    static List<String> signaturesOf(List<ElementArtifact> elements, List<ElementFailure> failures) {
        List<String> lines = new ArrayList<>();
        elements.forEach(element -> lines.add(ToolReading.signature(element)));
        failures.forEach(failure -> lines.add("failure " + failure.externalId() + ": " + failure.message()));
        lines.sort(null);
        return lines;
    }
}
