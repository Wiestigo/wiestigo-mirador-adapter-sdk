package com.rmm.adapter.api;

/**
 * The fields under which an adapter delivers comment text and thread
 * predecessor on an {@code ElementComment}, if they differ from the names in
 * {@link AdapterConventions}. This lets the sync core know the fields of a tool
 * without knowing the adapter type.
 *
 * @param bodyField      field of the comment text (not empty)
 * @param inReplyToField field of the identifier of the thread predecessor;
 *                       {@code null} for tools without comment threads
 */
public record CommentFields(String bodyField, String inReplyToField) {

    public CommentFields {
        if (bodyField == null || bodyField.isBlank()) {
            throw new IllegalArgumentException("bodyField must not be empty");
        }
        if (inReplyToField != null && inReplyToField.isBlank()) {
            throw new IllegalArgumentException("inReplyToField must not be blank — use null for tools without threads");
        }
    }
}
