package com.example.remoteservice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The in-memory tool of ONE binding: artifacts, links and comments, with its
 * own ids. {@link RemoteService} keeps one per binding, so data and state of
 * two connections (for example of two tenants) never touch. In a real
 * service, calls to your tool (its API, with the access this binding has
 * there) replace the in-memory data; the shapes of the answers stay.
 *
 * <p>The answers follow protocol version 4 (sections "Read calls", "Write
 * calls" and "Batch calls" of {@code docs/REMOTE_PROTOCOL.md}): pages with an
 * opaque cursor, creates with an id, updates with a receipt
 * ({@code appliedFields}), a comment without text skipped with a reason
 * ({@code skipped}), a failed element with {@code error} and a reason.</p>
 */
public class InMemoryTool {

    /** Maximum number of elements per page (the protocol allows up to 500 artifacts). */
    static final int PAGE_SIZE = 50;
    /** The field of the comment text (the conventional name; {@code docs/REMOTE_PROTOCOL.md}, "Elements"). */
    static final String COMMENT_BODY = "Body";

    /** A request the protocol does not allow; the service answers 400 with the reason. */
    static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    private final Map<String, Map<String, Object>> artifacts = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> relationships = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> comments = new LinkedHashMap<>();
    private long sequence;

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** {@code POST /read/artifacts}: one page, optionally only the ids named in {@code elementIds}. */
    public synchronized Map<String, Object> readArtifacts(Map<String, Object> request) {
        return page(selected(artifacts, request), request);
    }

    /** {@code POST /read/relationships}. */
    public synchronized Map<String, Object> readRelationships(Map<String, Object> request) {
        return page(selected(relationships, request), request);
    }

    /**
     * {@code POST /read/comments}: the last page names the artifacts whose
     * comments the call delivered COMPLETELY ({@code completeParentIds}): in
     * a full run every artifact, with {@code elementIds} only those of which
     * no comment was left out. For each artifact named there, Mirador deletes
     * the comments that are missing, so an artifact whose comments the call
     * left out must never appear in it.
     */
    public synchronized Map<String, Object> readComments(Map<String, Object> request) {
        List<Map<String, Object>> selected = selected(comments, request);
        Map<String, Object> page = page(selected, request);
        if (Boolean.TRUE.equals(page.get("lastPage"))) {
            Set<String> complete = new LinkedHashSet<>(artifacts.keySet());
            Set<Object> delivered = new LinkedHashSet<>();
            for (Map<String, Object> comment : selected) {
                delivered.add(comment.get("id"));
            }
            for (Map<String, Object> comment : comments.values()) {
                if (!delivered.contains(comment.get("id"))) {
                    complete.remove(text(comment, "commentToItemId"));
                }
            }
            page.put("completeParentIds", new ArrayList<>(complete));
        }
        return page;
    }

    // ------------------------------------------------------------------
    // Single writes
    // ------------------------------------------------------------------

    /** {@code POST /write/artifact/create}: answers the assigned id. */
    public synchronized Map<String, Object> createArtifact(Map<String, Object> request) {
        return createArtifactOutcome(element(request, "artifact"));
    }

    /**
     * {@code POST /write/artifact/update}: the fields sent replace those of
     * the same name (partial update, {@code supportsPartialUpdate}); the
     * receipt names them. An unknown artifact fails with a reason.
     */
    public synchronized Map<String, Object> updateArtifact(Map<String, Object> request) {
        return updateOutcome(element(request, "artifact"));
    }

    /** {@code POST /write/artifact/delete}: true when the artifact existed (otherwise 404; deletes are idempotent). */
    public synchronized boolean deleteArtifact(Map<String, Object> request) {
        return removeArtifact(text(request, "externalId"));
    }

    /** {@code POST /write/relationship/create}. */
    public synchronized Map<String, Object> createRelationship(Map<String, Object> request) {
        return createRelationshipOutcome(element(request, "relationship"));
    }

    /** {@code POST /write/relationship/delete}: true when the link existed. */
    public synchronized boolean deleteRelationship(Map<String, Object> request) {
        return relationships.remove(text(request, "externalId")) != null;
    }

    /** {@code POST /write/comment/create}: answers the assigned id; a comment without text is skipped with a reason. */
    public synchronized Map<String, Object> createComment(Map<String, Object> request) {
        return createCommentOutcome(element(request, "comment"));
    }

    // ------------------------------------------------------------------
    // Batch writes: exactly one outcome per element, in input order
    // ------------------------------------------------------------------

    /** {@code POST /write/artifacts/create} ({@code ARTIFACT_CREATE}). */
    public synchronized Map<String, Object> createArtifacts(Map<String, Object> request) {
        return outcomePerElement(request, "artifacts", this::createArtifactOutcome);
    }

    /** {@code POST /write/artifacts/update} ({@code ARTIFACT_UPDATE}): a receipt per element. */
    public synchronized Map<String, Object> updateArtifacts(Map<String, Object> request) {
        return outcomePerElement(request, "artifacts", this::updateOutcome);
    }

    /** {@code POST /write/artifacts/delete} ({@code ARTIFACT_DELETE}): an unknown artifact counts as deleted. */
    public synchronized Map<String, Object> deleteArtifacts(Map<String, Object> request) {
        return outcomePerId(request, this::removeArtifact);
    }

    /** {@code POST /write/relationships/create} ({@code RELATIONSHIP_CREATE}). */
    public synchronized Map<String, Object> createRelationships(Map<String, Object> request) {
        return outcomePerElement(request, "relationships", this::createRelationshipOutcome);
    }

    /** {@code POST /write/relationships/delete} ({@code RELATIONSHIP_DELETE}). */
    public synchronized Map<String, Object> deleteRelationships(Map<String, Object> request) {
        return outcomePerId(request, relationships::remove);
    }

    /** {@code POST /write/comments/create} ({@code COMMENT_CREATE}). */
    public synchronized Map<String, Object> createComments(Map<String, Object> request) {
        return outcomePerElement(request, "comments", this::createCommentOutcome);
    }

    /**
     * The answer of a batch that writes the elements of the array
     * {@code field}: the outcome of {@code write} for each element. An
     * element that is no JSON object fails alone; the others are written.
     */
    private static Map<String, Object> outcomePerElement(Map<String, Object> request, String field,
                                                         Function<Map<String, Object>, Map<String, Object>> write) {
        List<Object> outcomes = new ArrayList<>();
        for (Object element : elements(request, field)) {
            outcomes.add(element instanceof Map<?, ?> ? write.apply(asObject(element))
                    : error("the element is no JSON object"));
        }
        return outcomes(outcomes);
    }

    /**
     * The answer of a batch delete: {@code delete} is called with each id
     * in {@code externalIds}, and every id gets an empty outcome (deleted,
     * or already gone).
     */
    private static Map<String, Object> outcomePerId(Map<String, Object> request, Consumer<String> delete) {
        List<Object> outcomes = new ArrayList<>();
        for (Object id : elements(request, "externalIds")) {
            delete.accept(String.valueOf(id));
            outcomes.add(new LinkedHashMap<>());
        }
        return outcomes(outcomes);
    }

    // ------------------------------------------------------------------
    // Outcomes per element
    // ------------------------------------------------------------------

    private Map<String, Object> createArtifactOutcome(Map<String, Object> artifact) {
        if (!(artifact.get("fields") instanceof List)) {
            return error("the artifact carries no fields array");
        }
        String id = "WI-" + (++sequence);
        Map<String, Object> stored = new LinkedHashMap<>(artifact);
        stored.put("id", id);
        artifacts.put(id, stored);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("externalId", id);
        return outcome;
    }

    private Map<String, Object> updateOutcome(Map<String, Object> update) {
        String id = text(update, "id");
        Map<String, Object> stored = artifacts.get(id);
        if (stored == null) {
            return error("no artifact <" + id + "> in the tool");
        }
        if (!(update.get("fields") instanceof List<?> sent)) {
            return error("the artifact carries no fields array");
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Object field : (List<?>) stored.get("fields")) {
            merged.put(labelOf(field), field);
        }
        Set<String> applied = new LinkedHashSet<>();
        for (Object field : sent) {
            String label = labelOf(field);
            if (!label.isEmpty()) {
                merged.put(label, field);
                applied.add(label);
            }
        }
        stored.put("fields", new ArrayList<>(merged.values()));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("appliedFields", new ArrayList<>(applied));
        return outcome;
    }

    private Map<String, Object> createRelationshipOutcome(Map<String, Object> relationship) {
        String from = text(relationship, "fromItemId");
        String to = text(relationship, "toItemId");
        if (!artifacts.containsKey(from) || !artifacts.containsKey(to)) {
            return error("the link names an artifact the tool does not know (" + from + " -> " + to + ")");
        }
        String id = "LINK-" + (++sequence);
        Map<String, Object> stored = new LinkedHashMap<>(relationship);
        stored.put("id", id);
        relationships.put(id, stored);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("externalId", id);
        return outcome;
    }

    private Map<String, Object> createCommentOutcome(Map<String, Object> comment) {
        String parent = text(comment, "commentToItemId");
        if (!artifacts.containsKey(parent)) {
            return error("no artifact <" + parent + "> in the tool to comment on");
        }
        String body = null;
        if (comment.get("fields") instanceof List<?> fields) {
            for (Object field : fields) {
                if (field instanceof Map<?, ?> map && COMMENT_BODY.equals(map.get("name"))
                        && map.get("value") instanceof String value) {
                    body = value;
                }
            }
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            //The tool would have nothing to show: skipped, not failed
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("reason", "the comment has no text the tool can show");
            outcome.put("skipped", skipped);
            return outcome;
        }
        String id = "C-" + (++sequence);
        Map<String, Object> stored = new LinkedHashMap<>(comment);
        stored.put("id", id);
        comments.put(id, stored);
        outcome.put("externalId", id);
        return outcome;
    }

    /** Removes an artifact with its comments and links; false when it was unknown. */
    private boolean removeArtifact(String id) {
        if (artifacts.remove(id) == null) {
            return false;
        }
        comments.values().removeIf(comment -> id.equals(comment.get("commentToItemId")));
        relationships.values().removeIf(link -> id.equals(link.get("fromItemId")) || id.equals(link.get("toItemId")));
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The elements the request asks for: all, or exactly those in {@code elementIds} (unknown ids are dropped). */
    private static List<Map<String, Object>> selected(Map<String, Map<String, Object>> all,
                                                      Map<String, Object> request) {
        Object ids = request.get("elementIds");
        if (ids == null) {
            return new ArrayList<>(all.values());
        }
        if (!(ids instanceof List<?> wanted)) {
            throw new BadRequest("elementIds is null or an array of ids");
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Object id : wanted) {
            Map<String, Object> element = all.get(String.valueOf(id));
            if (element != null) {
                selected.add(element);
            }
        }
        return selected;
    }

    /** One page by cursor (an offset here, opaque to Mirador); {@code lastPage} exactly when no next cursor follows. */
    private static Map<String, Object> page(List<Map<String, Object>> all, Map<String, Object> request) {
        int start = 0;
        Object cursor = request.get("cursor");
        if (cursor != null) {
            try {
                start = Integer.parseInt(String.valueOf(cursor));
            } catch (NumberFormatException unknown) {
                throw new BadRequest("unknown cursor <" + cursor + ">");
            }
            if (start < 0 || start > all.size()) {
                throw new BadRequest("unknown cursor <" + cursor + ">");
            }
        }
        int end = Math.min(start + PAGE_SIZE, all.size());
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("elements", new ArrayList<>(all.subList(start, end)));
        page.put("failures", new ArrayList<>());
        page.put("readCount", (long) (end - start));
        boolean lastPage = end >= all.size();
        page.put("lastPage", lastPage);
        page.put("nextCursor", lastPage ? null : String.valueOf(end));
        return page;
    }

    private static Map<String, Object> element(Map<String, Object> request, String field) {
        Object element = request.get(field);
        if (!(element instanceof Map<?, ?>)) {
            throw new BadRequest("the request carries no object <" + field + ">");
        }
        return asObject(element);
    }

    private static List<?> elements(Map<String, Object> request, String field) {
        Object elements = request.get(field);
        if (!(elements instanceof List<?> list)) {
            throw new BadRequest("the request carries no array <" + field + ">");
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object element) {
        return (Map<String, Object>) element;
    }

    /** The identifier of a field in the tool: {@code label}, otherwise {@code name}. */
    private static String labelOf(Object field) {
        if (!(field instanceof Map<?, ?> map)) {
            return "";
        }
        Object label = map.get("label") != null ? map.get("label") : map.get("name");
        return label == null ? "" : String.valueOf(label);
    }

    private static String text(Map<String, Object> node, String field) {
        Object value = node.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> error(String reason) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("error", reason);
        return outcome;
    }

    private static Map<String, Object> outcomes(List<Object> outcomes) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("outcomes", outcomes);
        return answer;
    }
}
