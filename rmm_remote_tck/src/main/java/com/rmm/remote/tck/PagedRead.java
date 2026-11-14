package com.rmm.remote.tck;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pages of one read call, from the first cursor ({@code null}) to the
 * last page: collects their elements and requires that the read ends, no
 * cursor repeats and each page keeps the invariants of a page
 * ({@code docs/REMOTE_PROTOCOL.md}, "Read calls").
 */
final class PagedRead {

    /** Page limit of a read before the TCK reports that it does not terminate. */
    static final int MAX_PAGES = 1000;

    private final String path;
    private final String where;
    private final List<JsonNode> elements = new ArrayList<>();
    private final Set<String> cursors = new HashSet<>();
    private String cursor;
    private int pages;
    private boolean ended;

    /** A read of {@code path}; {@code where} starts the message of a violation. */
    PagedRead(String path, String where) {
        this.path = path;
        this.where = where;
    }

    /** Whether a page is still to be read. */
    boolean hasNext() {
        return !ended;
    }

    /** The cursor of the page to read next; {@code null} for the first page. */
    String cursor() {
        return cursor;
    }

    /** Takes the answer of the service for {@link #cursor()}. */
    void add(JsonNode answer) {
        JsonNode delivered = answer.path("elements");
        delivered.forEach(elements::add);
        JsonNode readCount = answer.path("readCount");
        if (readCount.isNumber() && readCount.asLong() < delivered.size()) {
            throw new ServiceContractViolation(where + ": " + path + " answered a page with readCount "
                    + readCount.asLong() + " and " + delivered.size() + " elements (readCount is at least the"
                    + " number of elements)");
        }
        boolean lastPage = answer.path("lastPage").asBoolean(false);
        JsonNode next = answer.path("nextCursor");
        if (lastPage) {
            if (!next.isNull() && !next.isMissingNode()) {
                throw new ServiceContractViolation(where + ": " + path + " answered its last page with a"
                        + " nextCursor (lastPage=true ⇔ nextCursor=null)");
            }
            ended = true;
            return;
        }
        if (next.isNull() || next.isMissingNode() || !cursors.add(next.asString(""))) {
            throw new ServiceContractViolation(where + ": " + path + " answered a page that is not the last one"
                    + " without a new nextCursor (lastPage=true ⇔ nextCursor=null, a cursor never repeats)");
        }
        cursor = next.asString("");
        if (++pages == MAX_PAGES) {
            throw new ServiceContractViolation(where + ": " + path + " did not end after " + MAX_PAGES + " pages");
        }
    }

    /** The elements of the pages taken so far. */
    List<JsonNode> elements() {
        return elements;
    }
}
