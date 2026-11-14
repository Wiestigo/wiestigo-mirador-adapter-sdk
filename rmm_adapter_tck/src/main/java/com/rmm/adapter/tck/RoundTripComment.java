package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementField;
import com.rmm.adapter.api.AdapterConventions;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.CommentFields;
import com.rmm.adapter.api.MetadataProvider;
import com.rmm.adapter.api.TargetOutcome;

import java.util.List;

/**
 * The comment of the comment round trip and the rules it is checked by: the
 * copy of the template that the TCK writes, the outcome of creating it, and
 * what the read delivers for it. Texts are compared without markup.
 */
final class RoundTripComment {

    private RoundTripComment() {
    }

    /** A copy of the template: the same id, name, fields and parent element. */
    static ElementComment copyOf(ElementComment template) {
        ElementComment copy = new ElementComment(template.getId(), template.getName(), template.getLabel(),
                template.getFields() == null ? List.of() : List.copyOf(template.getFields().values()),
                template.getCommentToItemId());
        copy.setProjectId(template.getProjectId());
        return copy;
    }

    /** The text the comment carries in the conventional body field; a comment without one is no template. */
    static String requireText(ElementComment comment) {
        String written = textOf(comment, AdapterConventions.COMMENT_BODY_FIELD);
        if (written == null || plainText(written).isEmpty()) {
            throw new TckViolation("roundTripComment() carries no text in the field <"
                    + AdapterConventions.COMMENT_BODY_FIELD + "> — the round trip"
                    + " needs a comment the tool can show");
        }
        return written;
    }

    /** The id of the comment that {@code createCommentWithOutcome} answered: created, not failed, not skipped. */
    static String requireCreated(TargetOutcome outcome) {
        if (outcome == null) {
            throw new TckViolation("createCommentWithOutcome must return a TargetOutcome — Mirador reads"
                    + " the id of the comment from it");
        }
        if (outcome.failure() != null) {
            throw new TckViolation("createCommentWithOutcome failed for the round-trip comment: "
                    + outcome.failure().getMessage());
        }
        if (outcome.skipped()) {
            throw new TckViolation("createCommentWithOutcome skipped the round-trip comment "
                    + outcome.notes() + " — roundTripComment() must be a comment the tool can show");
        }
        String commentId = outcome.externalId();
        if (commentId == null || commentId.isBlank()) {
            throw new TckViolation("createCommentWithOutcome must return the id the tool assigned —"
                    + " Mirador stores it as the comment's external reference; without it the next run would"
                    + " write the comment again");
        }
        return commentId;
    }

    /**
     * The read delivers the created comment exactly once, at its artifact
     * and with the text that was written.
     *
     * @param delivered the comments read under the id {@code commentId}
     * @param written   the text given to {@code createCommentWithOutcome}
     * @param instance  the binding; its metadata names the field the text is read in
     */
    static void requireReadBack(List<ElementComment> delivered, String commentId, String parentId, String written,
                                AdapterInstance instance) {
        if (delivered.isEmpty()) {
            throw new TckViolation("The comment created as <" + commentId + "> was not delivered by"
                    + " readComments — what the adapter writes must be readable, otherwise the next run"
                    + " would write it again");
        }
        if (delivered.size() > 1) {
            throw new TckViolation("readComments delivered the id <" + commentId + "> of the round-trip"
                    + " comment " + delivered.size() + " times — an id names exactly one comment, otherwise"
                    + " Mirador merges them into one");
        }
        ElementComment found = delivered.get(0);
        if (!parentId.equals(found.getCommentToItemIdValue())) {
            throw new TckViolation("The comment <" + commentId + "> was created at <" + parentId
                    + "> but is read at <" + found.getCommentToItemIdValue() + "> — the writer must keep"
                    + " the element a comment belongs to (commentToItemId)");
        }
        //Read as the sync core does: in the field the binding declares for the text
        //(metadata().commentFields()), otherwise the conventional one; written always in the conventional one
        String bodyField = instance.metadata().flatMap(MetadataProvider::commentFields)
                .map(CommentFields::bodyField)
                .orElse(AdapterConventions.COMMENT_BODY_FIELD);
        String read = textOf(found, bodyField);
        if (read == null || !plainText(read).equals(plainText(written))) {
            throw new TckViolation("readComments delivered the comment <" + commentId + "> with another"
                    + " text in <" + bodyField + "> than createCommentWithOutcome was given in <"
                    + AdapterConventions.COMMENT_BODY_FIELD + "> (compared without"
                    + " markup): <" + shortened(read == null ? null : plainText(read)) + "> instead of <"
                    + shortened(plainText(written)) + ">");
        }
    }

    /** A text for a message, at most 200 characters. */
    private static String shortened(String text) {
        return text == null || text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    /** The text of a comment in the field {@code field}; {@code null} if there is none. */
    private static String textOf(ElementComment comment, String field) {
        ElementField<?> body = comment.getFieldByName(new ElementName(field));
        return body == null || body.getValue() == null ? null : body.getValue().toString();
    }

    /** A text without markup (tags) and with collapsed whitespace. */
    private static String plainText(String text) {
        return text.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }
}
