package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.ElementFailure;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SyncRunContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the cursor semantics of a read phase. The host's sync core reads
 * every phase page by page until {@code lastPage}; a reader whose cursor
 * never terminates or repeats would hang a run there forever. This walker
 * finds that before deployment:
 *
 * <ul>
 *   <li>Termination: {@code lastPage} must be reached after at most
 *       {@code maxPages} pages.</li>
 *   <li>Progress: the same cursor value must not occur twice; a repeated
 *       cursor is an endless loop.</li>
 *   <li>The invariants of {@link SourcePage} itself (readCount,
 *       lastPage/nextCursor) are enforced by its constructor.</li>
 * </ul>
 */
public final class ReadContract {

    /** A read phase as a function: {@code readArtifacts}, {@code readRelationships} or {@code readComments}. */
    @FunctionalInterface
    public interface Phase<T> {
        SourcePage<T> read(SyncRunContext context, ReadScope scope, PageCursor cursor) throws AdapterException;
    }

    /** The result of a phase read to its end. */
    public record PhaseResult<T>(List<T> elements, List<ElementFailure> failures, int pages) {
    }

    /**
     * The pages of a read phase as the sync core fetches them: the first,
     * then the one after each page. A host may read the next page ahead on a
     * thread of its own as soon as it has a page. {@link #close()} ends a
     * read-ahead.
     */
    public interface Pages<T> extends AutoCloseable {

        /** The first page. */
        SourcePage<T> first() throws AdapterException;

        /** The page after {@code page}, which must not be the last page. */
        SourcePage<T> after(SourcePage<T> page) throws AdapterException;

        @Override
        void close();
    }

    /** Where a read phase gets its pages from ({@code ContractContext.paging()}). */
    public interface Paging {

        /**
         * The pages of the phase {@code phaseName}; the name starts with the
         * hook's name ({@code readArtifacts}, {@code readRelationships(...)}).
         */
        <T> Pages<T> pages(String phaseName, Phase<T> phase, SyncRunContext context, ReadScope scope);

        /** Every page on the calling thread, only when it is needed; the TCK's default. */
        static Paging sequential() {
            return new Paging() {
                @Override
                public <T> Pages<T> pages(String phaseName, Phase<T> phase, SyncRunContext context,
                                          ReadScope scope) {
                    return new Pages<>() {
                        @Override
                        public SourcePage<T> first() throws AdapterException {
                            return phase.read(context, scope, null);
                        }

                        @Override
                        public SourcePage<T> after(SourcePage<T> page) throws AdapterException {
                            return phase.read(context, scope, page.nextCursor());
                        }

                        @Override
                        public void close() {
                            //nothing was read ahead
                        }
                    };
                }

                @Override
                public String toString() {
                    return "Paging[sequential]";
                }
            };
        }
    }

    private ReadContract() {
    }

    /**
     * Reads the phase page by page until {@code lastPage} and checks the
     * cursor semantics. Throws {@link TckViolation} on a violation and
     * passes an {@link AdapterException} of the reader through unchanged.
     */
    public static <T> PhaseResult<T> readAll(String phaseName, Phase<T> phase, SyncRunContext context,
                                             ReadScope scope, int maxPages) throws AdapterException {
        return readAll(Paging.sequential(), phaseName, phase, context, scope, maxPages);
    }

    /** As above, with the pages from {@code paging}, for example with a host's read-ahead. */
    public static <T> PhaseResult<T> readAll(Paging paging, String phaseName, Phase<T> phase,
                                             SyncRunContext context, ReadScope scope, int maxPages)
            throws AdapterException {
        List<T> elements = new ArrayList<>();
        List<ElementFailure> failures = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        int pages = 0;
        try (Pages<T> source = paging.pages(phaseName, phase, context, scope)) {
            SourcePage<T> page = source.first();
            while (true) {
                if (page == null) {
                    throw new TckViolation(phaseName + ": the reader returned null instead of a SourcePage"
                            + " — return SourcePage.empty() for phases without content");
                }
                pages++;
                elements.addAll(page.elements());
                failures.addAll(page.failures());
                if (page.lastPage()) {
                    return new PhaseResult<>(List.copyOf(elements), List.copyOf(failures), pages);
                }
                //Progress: a repeated cursor value would mean reading the same
                //page forever; the host's sync core would never see an end
                if (!seenCursors.add(page.nextCursor().value())) {
                    throw new TckViolation(phaseName + ": cursor value <" + page.nextCursor().value()
                            + "> was returned twice — the reader would loop forever");
                }
                if (pages >= maxPages) {
                    throw new TckViolation(phaseName + ": no lastPage after " + maxPages
                            + " pages — the reader does not terminate (or raise maxPages() for very large sources)");
                }
                page = source.after(page);
            }
        }
    }
}
