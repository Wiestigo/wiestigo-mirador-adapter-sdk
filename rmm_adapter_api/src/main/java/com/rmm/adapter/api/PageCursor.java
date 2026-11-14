package com.rmm.adapter.api;

/**
 * Opaque continuation cursor of a read page. The sync core never interprets the
 * value: it only persists it in the run state and passes it back with the next
 * page call. The adapter decides what it means (start index, last external ID,
 * page number, ...). This covers every pagination model without the contract
 * preferring one, and the value stays serializable.
 *
 * <p><b>Pauses:</b> between two pages a run can pause for any length of time
 * (minutes to hours) while other runs work. The cursor must survive that: if it
 * refers to server-side state of the tool that expires, it carries enough to
 * resume reading from the last delivered position (last identifier, offset,
 * timestamp). See {@link SourceReader} and {@code docs/DEVELOPER_GUIDE.md}.
 */
public record PageCursor(String value) {

    public PageCursor {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Cursor value must not be empty — SourcePage.lastPage expresses the end of the pages");
        }
    }
}
