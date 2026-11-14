package com.rmm.remote.tck;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.rmm.remote.tck.OutcomeChecks.requireOutcome;
import static com.rmm.remote.tck.TransportChecks.requireRefused;
import static com.rmm.remote.tck.WireJson.abbreviated;
import static com.rmm.remote.tck.WireJson.contains;
import static com.rmm.remote.tck.WireJson.textsOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The TCK for remote adapter services, run over real HTTP against protocol
 * version 4 ({@code docs/REMOTE_PROTOCOL.md}). The author of a remote service,
 * written in any language, extends this class in a test, starts the service,
 * lets it trust the certificates of {@link RemoteServiceIdentities#pinned()}
 * and supplies its address ({@link #serviceUrl()}) and trust anchor
 * ({@link #serviceAnchor()}). No Mirador installation is needed: the TCK
 * speaks the wire protocol the way the host does for a connection of type
 * {@code REMOTE}. It uses TLS 1.3 or 1.2 with the host's AEAD cipher suites
 * only, and sends in every POST call the run's {@code deadline} and the values
 * of the binding's keys ({@code connection}, {@link #connectionValues()}).
 *
 * <p><b>The cases</b>, one protocol obligation each:</p>
 * <ol>
 *   <li>{@link #aRequestWithoutAPinnedClientCertificateIsRefused()}: the
 *       service requires a client certificate and accepts only pinned ones
 *       ("Transport"), on every path.</li>
 *   <li>{@link #theBindingHeaderMustNameTheBindingOfTheCertificate()}:
 *       {@code 403} when {@code X-RMM-Binding} is missing, repeated or names
 *       another binding than the certificate.</li>
 *   <li>{@link #twoBindingsKeepTheirDataApart()}: data and state per binding;
 *       what one binding creates, no other binding reads, changes or
 *       deletes.</li>
 *   <li>{@link #theServiceSpeaksProtocolVersion4()}: a version 4 descriptor
 *       per binding that the host can bind, and {@code /health}.</li>
 *   <li>{@link #writeAnswersFollowTheOutcomeRules()}: the outcome per element:
 *       id, receipt, {@code notApplied}, {@code notes}, {@code skipped},
 *       {@code error}, also in a batch.</li>
 * </ol>
 *
 * <p>Case 3 reads and writes and needs a service that declares both
 * directions with {@code ARTIFACTS}; case 5 only writes and needs
 * {@code FROM_RMM} with {@code ARTIFACTS} in {@code writes}. A case is skipped
 * for a service that lacks this. Each case deletes what it creates.
 * {@link #artifactToWrite} and {@link #commentToWrite} define the wire form of
 * the artifact and the comment the TCK writes. Every violation is a
 * {@link ServiceContractViolation}.</p>
 *
 * <p>The TCK accepts what the host accepts. It is stricter only where the
 * protocol demands it explicitly; the section "Testing" of the protocol
 * document lists those places.</p>
 *
 * <p>This TCK does not check the guarantees of the adapter contract (cursor
 * semantics, round trips, the cases of the adapter TCK in
 * {@code rmm_adapter_tck}).</p>
 */
public abstract class RemoteServiceContractTest {

    /** The protocol version header; the TCK sends it with every request. */
    public static final String PROTOCOL_HEADER = "X-RMM-Adapter-Protocol";
    /** The only protocol version the host speaks. */
    public static final int PROTOCOL_VERSION = 4;
    /** The binding header; the TCK sends what the case dictates. */
    public static final String BINDING_HEADER = "X-RMM-Binding";

    /** How far in the future the {@code deadline} lies that the TCK sends with every call. */
    static final Duration DEADLINE = Duration.ofMinutes(10);
    /** The path cases 1 and 2 use to probe a write: an idempotent delete. */
    static final String DELETE_PATH = "/write/artifact/delete";

    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private final Map<String, ServiceClient> clients = new HashMap<>();

    /**
     * The address of the running service, {@code https://...}, like
     * {@code url} of a connection of type {@code REMOTE}. The service must
     * require a client certificate and trust exactly
     * {@link RemoteServiceIdentities#pinned()}. Read only inside a case, that
     * is, after every {@code @BeforeEach} of the subclass.
     */
    protected abstract String serviceUrl();

    /**
     * The trust anchors of the service (PEM), like {@code tls.trust} of the
     * connection: a self-signed certificate of the service is pinned (no host
     * name check); a certificate authority is trusted with a host name check.
     */
    protected abstract String serviceAnchor();

    /**
     * The timeout per request of the TCK (headers and body), 30 s by default.
     * A request that exceeds it counts as unanswered, and in case 1 never as
     * a refusal.
     */
    protected Duration requestTimeout() {
        return Duration.ofSeconds(30);
    }

    /** The project space of the read and write requests ({@code externalProjectId}). */
    protected String externalProjectId() {
        return "PRJ-1";
    }

    /**
     * The values of the binding's keys, which the TCK sends in every envelope
     * as the object {@code connection}, the way the host sends the values of
     * the connection's {@code connectionKeys} (without {@code SECRET} keys):
     * text, number or boolean per key ({@code docs/REMOTE_PROTOCOL.md},
     * "Read calls"). Default: none, that is {@code "connection": {}}. A
     * service that declares required keys returns their values here.
     */
    protected Map<String, Object> connectionValues() {
        return Map.of();
    }

    /**
     * An artifact in wire form ({@code docs/REMOTE_PROTOCOL.md}, "Elements")
     * that the service can create and update. EVERY field must carry
     * {@code text}: the TCK recognizes its artifacts by it. Default: type
     * {@code Requirement} with the {@code STRING} fields {@code Name} and
     * {@code Description}. Without {@code id}; the TCK sets it for an update.
     * The service must be able to write it: an {@code error} for it is a
     * violation.
     */
    protected String artifactToWrite(String text) {
        ObjectNode artifact = json.createObjectNode();
        artifact.put("name", "Requirement");
        artifact.put("label", "Requirement");
        ArrayNode fields = artifact.putArray("fields");
        fields.add(stringField("Name", text));
        fields.add(stringField("Description", text));
        return artifact.toString();
    }

    /**
     * A comment in wire form on the artifact {@code artifactId} with the text
     * {@code text} (field {@code Body}); the service must be able to create
     * it.
     */
    protected String commentToWrite(String artifactId, String text) {
        ObjectNode comment = json.createObjectNode();
        comment.put("name", "Comment");
        comment.put("label", "Comment");
        comment.put("commentToItemId", artifactId);
        comment.putArray("fields").add(stringField("Body", text));
        return comment.toString();
    }

    // ------------------------------------------------------------------
    // Case 1: client certificate
    // ------------------------------------------------------------------

    /**
     * Case 1: the service requires a client certificate and accepts only
     * pinned ones. Without a certificate, with one the service does not know
     * ({@link RemoteServiceIdentities#unpinned()}), and with one it does not
     * know that names the binding of a pinned one
     * ({@link RemoteServiceIdentities#forgedFirst()}; anyone can issue a
     * certificate with the name {@code urn:rmm:binding:<A>}), the handshake
     * must fail (a TLS alert or a reset connection) or the service must answer
     * {@code 401}/{@code 403}. This holds on {@code /descriptor},
     * {@code /health}, {@code /read/artifacts} (with {@code TO_RMM}) and a
     * write (with {@code FROM_RMM}), even when the header names a binding. A
     * request that times out unanswered, or a refused connection, is not a
     * refusal. Before probing, the service must answer the first connection;
     * otherwise a refusal would prove nothing.
     */
    @Test
    public void aRequestWithoutAPinnedClientCertificateIsRefused() throws Exception {
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
        List<Probe> probes = probesOf(descriptorOf(a));
        requireRefused(client(null), "without a client certificate", a.bindingId(), probes);
        RemoteServiceIdentities.Identity unknown = RemoteServiceIdentities.unpinned();
        requireRefused(client(unknown), "with a client certificate it has not pinned", unknown.bindingId(), probes);
        requireRefused(client(RemoteServiceIdentities.forgedFirst()), "with a client certificate it has not pinned"
                + " that names the binding of a pinned one (anyone can issue urn:rmm:binding:" + a.bindingId()
                + ")", a.bindingId(), probes);
    }

    // ------------------------------------------------------------------
    // Case 2: X-RMM-Binding against the certificate
    // ------------------------------------------------------------------

    /**
     * Case 2: the header {@value #BINDING_HEADER} must name exactly the
     * binding the client certificate names
     * ({@code URI:urn:rmm:binding:<bindingId>}). When it is missing, appears
     * twice (even with the same value) or names the other binding, the
     * service answers {@code 403}: on every path of case 1, for both
     * connections. With the correct header it answers (a delete may also
     * answer {@code 404}: deletes are idempotent).
     */
    @Test
    public void theBindingHeaderMustNameTheBindingOfTheCertificate() throws Exception {
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
        RemoteServiceIdentities.Identity b = RemoteServiceIdentities.second();
        for (Probe probe : probesOf(descriptorOf(a))) {
            requireForbidden(a, probe, List.of(), "without the header");
            requireForbidden(a, probe, List.of(b.bindingId()), "naming the binding of another certificate");
            requireForbidden(b, probe, List.of(a.bindingId()), "naming the binding of another certificate");
            requireForbidden(a, probe, List.of(a.bindingId(), b.bindingId()), "twice, with two bindings");
            requireForbidden(a, probe, List.of(a.bindingId(), a.bindingId()), "twice, with the same binding");
            ServiceClient.Answer own = probe.send(client(a), List.of(a.bindingId()));
            if (!probe.answered(own)) {
                throw new ServiceContractViolation("Case 2: the service refused " + probe.path() + " with the header "
                        + BINDING_HEADER + " naming the binding of the certificate (" + own.status() + "): "
                        + abbreviated(own.body()));
            }
        }
    }

    private void requireForbidden(RemoteServiceIdentities.Identity identity, Probe probe, List<String> headers,
                                  String how) throws IOException {
        TransportChecks.requireForbidden(client(identity), identity, probe, headers, how);
    }

    /** A call that cases 1 and 2 use to exercise the checks of the service; {@code body} null: GET. */
    record Probe(String path, String body) {

        ServiceClient.Answer send(ServiceClient client, List<String> headers) throws IOException {
            return body == null ? client.get(path, headers) : client.post(path, body, headers);
        }

        /** Answered: 2xx; for a delete also 404 (idempotent, see "Transport" in the protocol document). */
        boolean answered(ServiceClient.Answer answer) {
            return answer.successful() || path.equals(DELETE_PATH) && answer.status() == 404;
        }
    }

    /**
     * The paths on which the service must check certificate and header: the
     * descriptor and {@code /health} always, {@code /read/artifacts} with
     * {@code TO_RMM}, and with {@code FROM_RMM} a delete of an unknown id,
     * which changes nothing.
     */
    private List<Probe> probesOf(JsonNode descriptor) {
        List<Probe> probes = new ArrayList<>(List.of(new Probe("/descriptor", null), new Probe("/health", null)));
        JsonNode directions = descriptor.path("capabilities").path("directions");
        if (contains(directions, "TO_RMM")) {
            probes.add(new Probe("/read/artifacts", readRequest(null, null)));
        }
        if (contains(directions, "FROM_RMM")) {
            probes.add(new Probe(DELETE_PATH, deleteRequest("tck-probe-" + UUID.randomUUID())));
        }
        return probes;
    }

    // ------------------------------------------------------------------
    // Case 3: two bindings kept apart
    // ------------------------------------------------------------------

    /**
     * Case 3: two bindings at the same service (two connections, for example
     * of two tenants) share neither data nor state. A and B each create an
     * artifact; each reads its own and not the other's, not even when it asks
     * for it by id ({@code elementIds}); an update and a delete by B with the
     * id of A change nothing for A; a comment A creates on its artifact and
     * reads back does not show up for B (when the service reads and writes
     * {@code COMMENTS}). Ids are opaque per binding: B may give its own
     * artifact the same id as A, so the TCK recognizes foreign artifacts by
     * their text.
     */
    @Test
    public void twoBindingsKeepTheirDataApart() throws Exception {
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
        RemoteServiceIdentities.Identity b = RemoteServiceIdentities.second();
        JsonNode descriptorA = descriptorOf(a);
        JsonNode descriptorB = descriptorOf(b);
        assumeTrue(readsAndWritesArtifacts(descriptorA) && readsAndWritesArtifacts(descriptorB),
                "the service does not read and write ARTIFACTS for both bindings - case 3 skipped");
        String markerA = "tck-binding-a-" + UUID.randomUUID();
        String markerB = "tck-binding-b-" + UUID.randomUUID();
        String idA = createArtifact(a, markerA, "Case 3");
        String idB = null;
        try {
            idB = createArtifact(b, markerB, "Case 3");
            requireSees(a, idA, markerA, markerB);
            requireSees(b, idB, markerB, markerA);
            List<JsonNode> named = readAll(b, List.of(idA), "Case 3");
            if (named.toString().contains(markerA)) {
                throw new ServiceContractViolation("Case 3: binding B read the artifact <" + idA + "> of binding A"
                        + " by its id (elementIds) — the service MUST keep data and state per binding"
                        + " (docs/REMOTE_PROTOCOL.md, \"Operation and configuration\"): "
                        + abbreviated(named.toString()));
            }
            String overwrite = "tck-overwritten-by-b-" + UUID.randomUUID();
            post(b, "/write/artifact/update", writeRequest("artifact", withId(artifactToWrite(overwrite), idA)),
                    null);
            post(b, DELETE_PATH, deleteRequest(idA), null);
            List<JsonNode> afterB = readAll(a, null, "Case 3");
            JsonNode stillA = afterB.stream().filter(element -> idA.equals(element.path("id").asString("")))
                    .findFirst().orElse(null);
            if (stillA == null || stillA.toString().contains(overwrite) || !stillA.toString().contains(markerA)) {
                throw new ServiceContractViolation("Case 3: an update and a delete of binding B with the id <" + idA
                        + "> of binding A changed the artifact of A — the service MUST keep data and state per"
                        + " binding. A reads now: " + (stillA == null ? "nothing" : abbreviated(stillA.toString())));
            }
            if (writesAndReadsComments(descriptorA) && writesAndReadsComments(descriptorB)) {
                requireCommentsApart(a, b, idA);
            }
        } finally {
            deleteQuietly(a, idA);
            if (idB != null) {
                deleteQuietly(b, idB);
            }
        }
    }

    /**
     * A creates a comment on its artifact and reads it back; B must not read
     * it. Without the first two steps the third would prove nothing.
     */
    private void requireCommentsApart(RemoteServiceIdentities.Identity a, RemoteServiceIdentities.Identity b,
                                      String idA) throws IOException {
        String comment = "tck-comment-of-a-" + UUID.randomUUID();
        JsonNode created = post(a, "/write/comment/create", writeRequest("comment", commentToWrite(idA, comment)),
                "Case 3");
        if (created.hasNonNull("error") || created.path("externalId").asString("").isBlank()) {
            throw new ServiceContractViolation("Case 3: creating a comment of binding A at its artifact <" + idA
                    + "> answered no externalId — the comment of the TCK carries a text, the service MUST create it: "
                    + abbreviated(created.toString()));
        }
        if (!readAllRaw(a, "/read/comments", "Case 3").toString().contains(comment)) {
            throw new ServiceContractViolation("Case 3: binding A does not read the comment <"
                    + created.path("externalId").asString("") + "> it created at its artifact (a created id MUST come"
                    + " back at the next read, docs/REMOTE_PROTOCOL.md, \"Write calls\")");
        }
        if (readAllRaw(b, "/read/comments", "Case 3").toString().contains(comment)) {
            throw new ServiceContractViolation("Case 3: binding B read the comment binding A wrote at its"
                    + " artifact — the service MUST keep data and state per binding");
        }
    }

    /**
     * The binding reads its own artifact (id and text) and none with the text
     * of the other binding. Only the text decides: an equal id may be the
     * binding's own artifact.
     */
    private void requireSees(RemoteServiceIdentities.Identity identity, String ownId, String ownMarker,
                             String otherMarker) throws IOException {
        List<JsonNode> elements = readAll(identity, null, "Case 3");
        boolean own = elements.stream().anyMatch(element -> ownId.equals(element.path("id").asString(""))
                && element.toString().contains(ownMarker));
        if (!own) {
            throw new ServiceContractViolation("Case 3: binding " + identity.bindingId() + " does not read the"
                    + " artifact <" + ownId + "> it created (a created id MUST come back as id at the next"
                    + " /read/artifacts, docs/REMOTE_PROTOCOL.md, \"Write calls\")");
        }
        if (elements.toString().contains(otherMarker)) {
            throw new ServiceContractViolation("Case 3: binding " + identity.bindingId() + " reads the artifact"
                    + " the other binding created — the service MUST keep data and state per binding"
                    + " (X-RMM-Binding, docs/REMOTE_PROTOCOL.md, \"Operation and configuration\")");
        }
    }

    // ------------------------------------------------------------------
    // Case 4: protocol version 4
    // ------------------------------------------------------------------

    /**
     * Case 4: the descriptor of each binding names {@code protocolVersion} 4
     * and follows the rules of {@code GET /descriptor}, so that the host binds
     * it: {@code systemKey}, {@code displayName} and {@code adapterVersion};
     * directions, hierarchy model, and the mandatory fields {@code reads} and
     * {@code writes} matching the directions; known bulk operations whose
     * phase is in {@code writes}; {@code supportsRelationshipUpdate} only
     * with {@code RELATIONSHIPS} in {@code writes}; a {@code rateLimit} of at
     * least one request per second; a complete {@code pathHierarchy}
     * (mandatory with {@code PATH_ATTRIBUTE}); identifier kinds with a valid
     * name, pattern and format, within the host's limits (at most
     * {@value IdentifierKindChecks#MAX_IDENTIFIER_KINDS}, patterns up to
     * {@value IdentifierKindChecks#MAX_PATTERN_LENGTH} characters, every
     * other field up to {@value IdentifierKindChecks#MAX_KIND_FIELD_LENGTH})
     * and with a passing round trip;
     * {@code connectionKeys} that follow the rules of the protocol.
     * {@code GET /health} answers 2xx.
     */
    @Test
    public void theServiceSpeaksProtocolVersion4() throws Exception {
        for (RemoteServiceIdentities.Identity identity : List.of(RemoteServiceIdentities.first(),
                RemoteServiceIdentities.second())) {
            DescriptorChecks.requireVersion4(identity, descriptorOf(identity));
            ServiceClient.Answer health = client(identity).get("/health", List.of(identity.bindingId()));
            if (!health.successful()) {
                throw new ServiceContractViolation("Case 4: GET /health of binding " + identity.bindingId()
                        + " answered " + health.status() + " — a healthy service answers 2xx");
            }
        }
    }

    // ------------------------------------------------------------------
    // Case 5: outcome per element
    // ------------------------------------------------------------------

    /**
     * Case 5: write answers follow the rules of the outcome per element
     * ({@code docs/REMOTE_PROTOCOL.md}, "Write calls" and "Batch calls"). The
     * well-formed elements of the TCK must not fail ({@code error}); a create
     * names an id, also next to a receipt; no field appears both in the
     * receipt and in {@code notApplied}, and every reason is a text;
     * {@code notes} are non-empty texts; {@code skipped} is allowed only when
     * creating a comment, with a reason and without id, receipt or field
     * reasons ({@code "skipped": false} means not skipped). Declared bulk
     * operations ({@code ARTIFACT_CREATE}, {@code ARTIFACT_UPDATE},
     * {@code COMMENT_CREATE}) answer exactly one outcome per element, in input
     * order; an update in a batch carries a receipt. An answer may name field
     * identifiers the TCK did not send: the host ignores them.
     */
    @Test
    public void writeAnswersFollowTheOutcomeRules() throws Exception {
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
        JsonNode descriptor = descriptorOf(a);
        assumeTrue(writesArtifacts(descriptor),
                "the service does not write ARTIFACTS (FROM_RMM) - case 5 skipped");
        Set<String> bulk = textsOf(descriptor.path("capabilities").path("bulkOperations"), "bulkOperations");
        boolean comments = contains(descriptor.path("capabilities").path("writes"), "COMMENTS");
        List<String> created = new ArrayList<>();
        try {
            String id = createArtifact(a, "tck-outcome-" + UUID.randomUUID(), "Case 5");
            created.add(id);
            String update = withId(artifactToWrite("tck-outcome-update-" + UUID.randomUUID()), id);
            JsonNode updated = post(a, "/write/artifact/update", writeRequest("artifact", update), "Case 5");
            requireOutcome("/write/artifact/update", updated, Expect.UPDATE);
            if (comments) {
                JsonNode comment = post(a, "/write/comment/create",
                        writeRequest("comment", commentToWrite(id, "tck comment " + UUID.randomUUID())), "Case 5");
                requireOutcome("/write/comment/create", comment, Expect.COMMENT);
            }
            if (bulk.contains("ARTIFACT_CREATE")) {
                List<String> artifacts = List.of(artifactToWrite("tck-batch-1-" + UUID.randomUUID()),
                        artifactToWrite("tck-batch-2-" + UUID.randomUUID()));
                JsonNode outcomes = batch(a, "/write/artifacts/create", "artifacts", artifacts);
                for (int index = 0; index < artifacts.size(); index++) {
                    JsonNode outcome = outcomes.get(index);
                    if (outcome.hasNonNull("externalId")) {
                        created.add(outcome.path("externalId").asString(""));
                    }
                    requireOutcome("/write/artifacts/create #" + index, outcome, Expect.CREATE_IN_BATCH);
                }
            }
            if (bulk.contains("ARTIFACT_UPDATE")) {
                List<String> updates = List.of(withId(artifactToWrite("tck-batch-update-" + UUID.randomUUID()), id));
                JsonNode outcomes = batch(a, "/write/artifacts/update", "artifacts", updates);
                requireOutcome("/write/artifacts/update #0", outcomes.get(0), Expect.UPDATE_IN_BATCH);
            }
            if (bulk.contains("COMMENT_CREATE") && comments) {
                List<String> batchComments = List.of(commentToWrite(id, "tck batch comment " + UUID.randomUUID()));
                JsonNode outcomes = batch(a, "/write/comments/create", "comments", batchComments);
                requireOutcome("/write/comments/create #0", outcomes.get(0), Expect.COMMENT_IN_BATCH);
            }
        } finally {
            created.forEach(id -> deleteQuietly(a, id));
        }
    }

    /** What an outcome must carry (id, receipt) and may be (skipped). */
    enum Expect {
        UPDATE(false, false, false), UPDATE_IN_BATCH(false, false, true), COMMENT(true, true, false),
        COMMENT_IN_BATCH(true, true, false), CREATE_IN_BATCH(true, false, false);

        final boolean id;
        final boolean skippable;
        final boolean receipt;

        Expect(boolean id, boolean skippable, boolean receipt) {
            this.id = id;
            this.skippable = skippable;
            this.receipt = receipt;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The client of an identity (null: without a client certificate), created
     * once per case and certificate. The certificate is part of the key
     * because {@link RemoteServiceIdentities#forgedFirst()} names the same
     * binding as {@link RemoteServiceIdentities#first()}.
     */
    private ServiceClient client(RemoteServiceIdentities.Identity identity) {
        String name = identity == null ? "without client certificate" : "binding " + identity.bindingId()
                + ", certificate " + identity.certificate().getSerialNumber().toString(16);
        return clients.computeIfAbsent(name, key -> new ServiceClient(key, serviceUrl(), serviceAnchor(), identity,
                requestTimeout()));
    }

    /** {@code GET /descriptor} of a binding with its header; the service must answer. */
    private JsonNode descriptorOf(RemoteServiceIdentities.Identity identity) {
        ServiceClient.Answer answer;
        try {
            answer = client(identity).get("/descriptor", List.of(identity.bindingId()));
        } catch (IOException noAnswer) {
            throw new ServiceContractViolation("The service did not answer GET /descriptor of binding "
                    + identity.bindingId() + " with its pinned certificate (" + noAnswer + ") — is "
                    + serviceUrl() + " running, does it trust RemoteServiceIdentities.pinned(), and does it offer"
                    + " TLS 1.3 or TLS 1.2 with ECDHE and AEAD (the only cipher suites of Mirador)?");
        }
        if (!answer.successful()) {
            throw new ServiceContractViolation("The service refused GET /descriptor of binding "
                    + identity.bindingId() + " with its pinned certificate and header (" + answer.status() + "): "
                    + abbreviated(answer.body()) + " — does it trust RemoteServiceIdentities.pinned()?");
        }
        return parse(answer.body(), "GET /descriptor");
    }

    /** A call the service must accept; {@code where} null: send only (the result does not count). */
    private JsonNode post(RemoteServiceIdentities.Identity identity, String path, String body, String where)
            throws IOException {
        ServiceClient.Answer answer = client(identity).post(path, body, List.of(identity.bindingId()));
        if (where == null) {
            return null;
        }
        if (!answer.successful()) {
            throw new ServiceContractViolation(where + ": POST " + path + " of binding " + identity.bindingId()
                    + " answered " + answer.status() + ": " + abbreviated(answer.body()));
        }
        return answer.body() == null || answer.body().isBlank() ? json.createObjectNode()
                : parse(answer.body(), "POST " + path);
    }

    private String createArtifact(RemoteServiceIdentities.Identity identity, String text, String where)
            throws IOException {
        JsonNode answer = post(identity, "/write/artifact/create", writeRequest("artifact", artifactToWrite(text)),
                where);
        String id = answer.path("externalId").asString("");
        if (id.isBlank()) {
            throw new ServiceContractViolation(where + ": /write/artifact/create answered no externalId: " + answer);
        }
        return id;
    }

    /** A batch call; requires exactly one outcome per element (by count). */
    private JsonNode batch(RemoteServiceIdentities.Identity identity, String path, String field, List<String> elements)
            throws IOException {
        ObjectNode request = envelope();
        ArrayNode array = request.putArray(field);
        for (String element : elements) {
            array.add(parse(element, "an element of the TCK"));
        }
        JsonNode answer = post(identity, path, request.toString(), "Case 5");
        JsonNode outcomes = answer.path("outcomes");
        if (!outcomes.isArray() || outcomes.size() != elements.size()) {
            throw new ServiceContractViolation("Case 5: " + path + " answered " + (outcomes.isArray()
                    ? outcomes.size() + " outcomes" : "no outcomes array") + " for " + elements.size()
                    + " elements — a batch answers exactly one outcome per element, in input order: " + answer);
        }
        return outcomes;
    }

    private void deleteQuietly(RemoteServiceIdentities.Identity identity, String id) {
        try {
            client(identity).post(DELETE_PATH, deleteRequest(id), List.of(identity.bindingId()));
        } catch (IOException | RuntimeException ignored) {
            //Cleanup after the case; deletes are idempotent
        }
    }

    /** All artifacts of a binding across all pages; {@code elementIds} null: a full run. */
    private List<JsonNode> readAll(RemoteServiceIdentities.Identity identity, List<String> elementIds, String where)
            throws IOException {
        return readPages(identity, "/read/artifacts", elementIds, where);
    }

    private List<JsonNode> readAllRaw(RemoteServiceIdentities.Identity identity, String path, String where)
            throws IOException {
        return readPages(identity, path, null, where);
    }

    private List<JsonNode> readPages(RemoteServiceIdentities.Identity identity, String path, List<String> elementIds,
                                     String where) throws IOException {
        PagedRead read = new PagedRead(path, where);
        while (read.hasNext()) {
            read.add(post(identity, path, readRequest(elementIds, read.cursor()), where));
        }
        return read.elements();
    }

    /**
     * The envelope of every POST call: the values of the binding's keys
     * ({@code connection}, {@link #connectionValues()}), run, project space
     * and the run's deadline ({@code deadline}, ISO 8601, {@link #DEADLINE}
     * from now). The host sends them with every read and write call.
     */
    private ObjectNode envelope() {
        ObjectNode request = json.createObjectNode();
        request.set("connection", json.valueToTree(connectionValues()));
        request.put("runId", UUID.randomUUID().toString());
        request.put("externalProjectId", externalProjectId());
        request.put("deadline", Instant.now().plus(DEADLINE).truncatedTo(ChronoUnit.SECONDS).toString());
        return request;
    }

    private String readRequest(List<String> elementIds, String cursor) {
        ObjectNode request = envelope();
        request.putNull("changedSince");
        if (elementIds == null) {
            request.putNull("elementIds");
        } else {
            ArrayNode ids = request.putArray("elementIds");
            elementIds.forEach(ids::add);
        }
        if (cursor == null) {
            request.putNull("cursor");
        } else {
            request.put("cursor", cursor);
        }
        return request.toString();
    }

    private String writeRequest(String field, String element) {
        ObjectNode request = envelope();
        request.set(field, parse(element, "an element of the TCK"));
        return request.toString();
    }

    private String deleteRequest(String id) {
        return envelope().put("externalId", id).toString();
    }

    private String withId(String element, String id) {
        ObjectNode node = (ObjectNode) parse(element, "an element of the TCK");
        node.put("id", id);
        return node.toString();
    }

    private ObjectNode stringField(String name, String value) {
        return json.createObjectNode().put("name", name).put("label", name).put("type", "STRING").put("value", value);
    }

    private static boolean readsAndWritesArtifacts(JsonNode descriptor) {
        JsonNode capabilities = descriptor.path("capabilities");
        return contains(capabilities.path("directions"), "TO_RMM") && contains(capabilities.path("reads"),
                "ARTIFACTS") && writesArtifacts(descriptor);
    }

    /** Whether the service writes artifacts: {@code FROM_RMM} with {@code ARTIFACTS} in {@code writes}. */
    private static boolean writesArtifacts(JsonNode descriptor) {
        JsonNode capabilities = descriptor.path("capabilities");
        return contains(capabilities.path("directions"), "FROM_RMM")
                && contains(capabilities.path("writes"), "ARTIFACTS");
    }

    private static boolean writesAndReadsComments(JsonNode descriptor) {
        JsonNode capabilities = descriptor.path("capabilities");
        return contains(capabilities.path("reads"), "COMMENTS") && contains(capabilities.path("writes"), "COMMENTS");
    }

    private JsonNode parse(String body, String what) {
        try {
            return json.readTree(body);
        } catch (RuntimeException unreadable) {
            throw new ServiceContractViolation(what + " is no JSON: " + abbreviated(body));
        }
    }
}
