package com.rmm.adapter.api;

import java.util.List;

/**
 * One result page of the {@link SourceReader}, with error isolation per
 * element.
 *
 * @param elements elements delivered and converted successfully.
 * @param failures elements the tool delivered but that could not be converted;
 *     they do not abort the page.
 * @param readCount number of elements the tool delivered for this page,
 *     including the failed ones. Only with it can the sync core detect the end
 *     of the pages correctly when conversions fail.
 * @param nextCursor cursor of the next page; null exactly if {@code lastPage}
 *     is true.
 * @param lastPage true: no further page.
 * @param deletedIds identifiers the tool has deleted since the instant of a
 *     delta run; optional, empty for tools without a deletion feed. The core
 *     treats them like "named and not delivered": the element is deleted or
 *     marked in Mirador, limited to exactly these identifiers. They may be
 *     delivered on every page or collected on the last one.
 * @param completeParentIds only on comment and attachment pages: the parent
 *     artifacts whose comments (attachments) are delivered completely with
 *     this page, read without error and without truncation. Optional; empty =
 *     no statement. For these parents the core compares the comments in
 *     Mirador that carry a reference of the system with the delivered ones and
 *     deletes what is missing, also in delta and identifier runs, where it
 *     otherwise knows only the deletion feed. An adapter therefore needs no
 *     comment state of its own across runs. Never list an element with a read
 *     error.
 */
public record SourcePage<T>(List<T> elements,
                            List<ElementFailure> failures,
                            int readCount,
                            PageCursor nextCursor,
                            boolean lastPage,
                            List<String> deletedIds,
                            List<String> completeParentIds) {

    /** A page without a deletion feed: the usual form. */
    public SourcePage(List<T> elements, List<ElementFailure> failures, int readCount, PageCursor nextCursor,
                      boolean lastPage) {
        this(elements, failures, readCount, nextCursor, lastPage, List.of(), List.of());
    }

    /** A page with a deletion feed, without a completeness statement per parent element. */
    public SourcePage(List<T> elements, List<ElementFailure> failures, int readCount, PageCursor nextCursor,
                      boolean lastPage, List<String> deletedIds) {
        this(elements, failures, readCount, nextCursor, lastPage, deletedIds, List.of());
    }

    public SourcePage {
        elements = elements == null ? List.of() : List.copyOf(elements);
        failures = failures == null ? List.of() : List.copyOf(failures);
        deletedIds = deletedIds == null ? List.of() : List.copyOf(deletedIds);
        completeParentIds = completeParentIds == null ? List.of() : List.copyOf(completeParentIds);
        if (readCount < elements.size()) {
            throw new IllegalArgumentException(
                    "readCount counts all delivered elements and cannot be less than elements.size()");
        }
        if (lastPage == (nextCursor != null)) {
            throw new IllegalArgumentException(
                    "nextCursor must be absent exactly when lastPage is true");
        }
    }

    /** An empty last page, for a declared phase that has nothing to deliver. */
    public static <T> SourcePage<T> empty() {
        return new SourcePage<>(List.of(), List.of(), 0, null, true);
    }
}
