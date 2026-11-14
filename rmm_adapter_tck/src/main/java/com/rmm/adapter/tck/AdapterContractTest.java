package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementField;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.io_elements.layer_infrastructure.io_mapping.ExceptionIoMapping;
import com.io_elements.layer_infrastructure.io_mapping.IoMappingJsonReader;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterConventions;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AttachmentContent;
import com.rmm.adapter.api.BulkOperation;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigScope;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.IdentifierKind;
import com.rmm.adapter.api.ImageReferenceForm;
import com.rmm.adapter.api.MetadataProvider;
import com.rmm.adapter.api.RateLimit;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The contract test of the adapter interface (TCK). An adapter author extends
 * this class, returns the adapter from {@link #adapter()} and inherits the
 * contract tests; no running Mirador is needed.
 *
 * <p>Three groups of tests:</p>
 * <ul>
 *   <li><b>Always</b> (no tool needed): the descriptor is constant and matches
 *       the API version, the configuration keys are well formed, and the bound
 *       instance is consistent with its capabilities. The host registers an
 *       adapter that fails one of these checks as {@code DEFECT}.</li>
 *   <li><b>Against the adapter's own tool</b> (opt-in with
 *       {@link #liveRunContext()}, for example a test project): every read
 *       phase terminates with sane cursors, a declared delta read works, and,
 *       with {@link #roundTripArtifact()}, {@link #roundTripComment()} and
 *       {@link #roundTripAttachment()}, what the adapter writes can be read
 *       back and deleted. Without the opt-in these tests are skipped, not
 *       passed.</li>
 *   <li><b>The binding cases 1 to 7</b>:
 *     <ol>
 *       <li>Two bindings work side by side without cross-talk (opt-in with
 *           {@link #secondBindingConfiguration()}).</li>
 *       <li>Network traffic goes only through {@code context.http()} (opt-in
 *           with {@link #networkTool()}).</li>
 *       <li>The classes of {@link #adapterClasses()} keep no process-wide
 *           state.</li>
 *       <li>{@code close()} releases, and the same connection binds again with
 *           the same results; a hook stops at the deadline of its run; a new
 *           binding reloads what cleared caches held; the run store is empty
 *           after the run; no response stream stays open.</li>
 *       <li>Configuration comes only from the context, and no {@code PATH}
 *           key has scope {@code BINDING}.</li>
 *       <li>A plugin unit carries a manifest that matches its descriptor, and
 *           its mapping template.</li>
 *       <li>{@code purge()} changes no content of the tool.</li>
 *     </ol>
 *     In addition, a cursor survives a long pause between two pages.</li>
 * </ul>
 *
 * <p>Every binding gets a {@link ContractContext} with the values of
 * {@link #configuration()} (the connection) and
 * {@link #platformConfiguration()} (the deployment); by default a
 * {@link TckAdapterContext}. As in the host, every call of a read or write
 * hook runs inside a run of the connection ({@link ContractContext#inRun}), so
 * {@code runStore()}, {@code executor().submit} and
 * {@code http().currentRun()} behave as in the host. {@code bind()},
 * {@code close()}, {@code checkHealth()}, {@code purge()} and the metadata
 * calls run outside a run. After every test the TCK checks that
 * {@link #networkTool()} refused no request: writes, attachments and
 * {@code purge()} send only through {@code context.http()} too.</p>
 *
 * <p><b>Configuration rules</b>, as the host enforces them: keys of scope
 * {@code PLATFORM} and upper limits {@code <name>.max} belong in
 * {@link #platformConfiguration()}; in {@link #configuration()} they make
 * building the context fail. A secret of the connection ({@code SECRET},
 * {@code CERTIFICATE}, or a key that is secret by its name) belongs in
 * {@link #configuration()}; the host never reads it from the deployment. A
 * value of the connection has at most 4000 characters. The stores return a
 * document as the host's database reads it back, not as the text that was
 * written.</p>
 *
 * <p><b>HTTP path:</b> redirects, OAuth and {@code cookies()} of the TCK's
 * {@code context.http()} behave as in the host ({@link TckAdapterHttp}); the
 * same checks ({@link AdapterHttpSemantics}) run against both.</p>
 *
 * <p><b>Running against a host:</b> a test class that overrides
 * {@link #contractHost()} runs the same cases against the context of the host
 * (its HTTP path, stores, runs and threads), with the hooks called and the
 * pages fetched as the host does ({@link ContractContext#hosted},
 * {@link ContractContext#paging()}). The cases then also check what they rely
 * on in the context; such a failure names the context, not the adapter. This
 * is reserved for the test harness of the Mirador host
 * ({@link ContractHost#ACCEPTED_PACKAGES}); the suite of an adapter runs
 * against the default.</p>
 */
public abstract class AdapterContractTest {

    /**
     * The adapter under test: a fresh, equivalent instance per call, or the
     * same object every time. Case 1 is complete only with a fresh instance:
     * the TCK then also reads the second binding as the first binding of its
     * type, and so detects a cache per project or address that the type keeps
     * from the first binding.
     */
    protected abstract ToolAdapter adapter();

    /**
     * The configuration of the connection under test, with the relative keys
     * of the declaration (for example {@code home.url}). A required key
     * without a value fails the binding tests with a message that names the
     * key; the host does not bind such a connection but holds it as
     * {@code NOT_CONFIGURED}. Default: empty.
     */
    protected Map<String, String> configuration() {
        return Map.of();
    }

    /**
     * The values of the deployment: keys of scope {@code PLATFORM} (paths,
     * upper limits {@code <name>.max}), which only the operator sets in the
     * host. As there, they come from here only; such a key in
     * {@link #configuration()} makes building the context fail. Relative
     * keys. Default: empty.
     */
    protected Map<String, String> platformConfiguration() {
        return Map.of();
    }

    /**
     * Opt-in for case 2: the fake tool that {@link #configuration()} points
     * at, started with {@link FakeHttpTool#start()} in a {@code @BeforeEach}
     * and given the answers of the tool. It answers only requests sent through
     * {@code context.http()}, and the TCK checks that every request of a
     * binding carries the header of that binding. Empty (the default) skips
     * case 2, as for an adapter without network (in memory, file based).
     */
    protected Optional<FakeHttpTool> networkTool() {
        return Optional.empty();
    }

    /**
     * An explicit opt-out of case 2 with a reason, for example for an adapter
     * that still builds its own HTTP client. The reason is printed; the case
     * counts as skipped, not passed. Default: empty.
     */
    protected Optional<String> networkOptOut() {
        return Optional.empty();
    }

    /**
     * Expires state on the tool's side (search sessions, result sets,
     * continuation tokens), as after a long pause of a run. Default:
     * {@link FakeHttpTool#expireState()} of {@link #networkTool()}; if that
     * tool registered no state ({@link FakeHttpTool#onExpiry}), the pause test
     * fails, and without {@code networkTool()} it is skipped. Override it for
     * a tool of your own; the TCK then takes the state as expired.
     */
    protected void expireToolState() {
        networkTool().ifPresent(FakeHttpTool::expireState);
    }

    /** How long the pause between two pages really lasts, on top of expiring the tool's state. Default: 100 ms. */
    protected Duration pauseBetweenPages() {
        return Duration.ofMillis(100);
    }

    /** The upper limit of {@link #deadlineGrace()}. */
    public static final Duration MAX_DEADLINE_GRACE = Duration.ofSeconds(5);

    /**
     * How long a hook may keep working after the deadline of its run (case 4).
     * Default and upper limit: {@link #MAX_DEADLINE_GRACE}. A test may only
     * shorten it; a longer, negative or {@code null} value fails the case.
     */
    protected Duration deadlineGrace() {
        return MAX_DEADLINE_GRACE;
    }

    /**
     * Opt-in for the tests against the real tool: the run context of a test
     * project. Empty (the default) skips the read, delta and round-trip tests
     * and, in the binding cases, everything that reads from the tool.
     */
    protected Optional<SyncRunContext> liveRunContext() {
        return Optional.empty();
    }

    /**
     * Opt-in for the round-trip tests: an artifact in the mapped view that the
     * tool can create in the test project (the tool's type as label, required
     * fields filled). The TCK sets ids on the returned object, so return a new
     * one per call. Every artifact the TCK creates is deleted again. Empty
     * (the default) skips these tests.
     */
    protected Optional<ElementArtifact> roundTripArtifact() {
        return Optional.empty();
    }

    /**
     * Opt-in for {@link #relationshipChangesFollowTheDeclaredUpdatePath()}:
     * the name of a relationship type that the tool accepts between two
     * artifacts created from {@link #roundTripArtifact()}. Empty (the
     * default) skips that test.
     */
    protected Optional<String> roundTripRelationshipType() {
        return Optional.empty();
    }

    /**
     * Opt-in for the comment round trip: a comment in the mapped view that the
     * tool accepts at an artifact created from {@link #roundTripArtifact()}
     * and can show, with its text in the field
     * {@link AdapterConventions#COMMENT_BODY_FIELD}. The TCK sets the parent
     * element ({@code commentToItemId}) itself. Empty (the default) skips the
     * round trip.
     */
    protected Optional<ElementComment> roundTripComment() {
        return Optional.empty();
    }

    /**
     * A comment in the mapped view with {@code text} in the field
     * {@link AdapterConventions#COMMENT_BODY_FIELD}: the usual template for
     * {@link #roundTripComment()}.
     */
    protected static ElementComment commentWithText(String text) {
        ElementName body = new ElementName(AdapterConventions.COMMENT_BODY_FIELD);
        ElementComment comment = ElementComment.ofNameAndLabel(new ElementName("Comment"),
                new ElementLabel("Comment"));
        comment.addField(new ElementFieldString(body, new ElementLabel(body.getName()), text));
        return comment;
    }

    /**
     * The template of the attachment round trip: a small file that the tool
     * accepts in the test project. Default: a text file; override it for a
     * tool that restricts names or types. {@code Optional.empty()} skips the
     * round trip. The TCK sets the parent element ({@code parentId}), the
     * length and the bytes itself.
     */
    protected Optional<ElementAttachment> roundTripAttachment() {
        ElementAttachment attachment = new ElementAttachment(new ElementId("tck-attachment"), null,
                "rmm-tck-attachment.txt");
        attachment.setContentType("text/plain");
        return Optional.of(attachment);
    }

    /** The bytes of the round-trip attachment; small, so that no tool refuses them. */
    private static final byte[] ROUND_TRIP_BYTES =
            "Mirador TCK attachment round trip".getBytes(StandardCharsets.UTF_8);

    /** The most pages per read phase before the TCK reports a read that does not terminate. Default: 1000. */
    protected int maxPages() {
        return 1000;
    }

    /**
     * Opt-in for case 1: the configuration of a second binding of the same
     * adapter that reaches another fake tool (another file, another project,
     * another test account) with other content. Another page size alone
     * proves nothing; keep everything else as in {@link #configuration()}.
     * The strongest setup is the same address with another account or project
     * (like two tenants on one cloud tool): only then does a client show up
     * that the type caches per address. Relative keys. Empty (the default)
     * skips case 1; cases 2 and 7 also use the second binding if there is
     * one.
     */
    protected Optional<Map<String, String>> secondBindingConfiguration() {
        return Optional.empty();
    }

    /**
     * The client identity of the connection of {@link #configuration()}, for
     * an adapter that declares a key of type {@code CLIENT_IDENTITY}: a PEM
     * with the certificate and its unencrypted private key (PKCS#8), as the
     * host creates and keeps it for the connection. {@code context.http()}
     * presents it on every {@code https} request; {@code config()} shows only
     * the certificate. If the certificate names its binding
     * ({@code URI:urn:rmm:binding:<bindingId>}), the context has that binding
     * id. Without an identity (the default) the TCK does not build the
     * context of such an adapter, as the host does not bind it.
     * {@link FakeHttpTool#startHttps} gives a tool that demands a client
     * certificate.
     */
    protected Optional<String> clientIdentity() {
        return Optional.empty();
    }

    /**
     * The client identity of the connection of
     * {@link #secondBindingConfiguration()} (case 1): another one than
     * {@link #clientIdentity()}, whose certificate names another binding, as
     * every connection in the host has its own. Default:
     * {@link #clientIdentity()}; with an identity and a second configuration,
     * case 1 then fails and asks for an override. With the same binding, a
     * service that keeps its data per {@code X-RMM-Binding} would see one
     * binding twice, and case 1 could not find cross-talk.
     */
    protected Optional<String> secondBindingClientIdentity() {
        return clientIdentity();
    }

    /**
     * The classes that case 3 searches for process-wide state. Default: all
     * classes from the code source of the adapter class (directory or jar)
     * except the packages of the TCK and of the contract. If the code source
     * cannot be determined, the case fails with a message; then, or if the
     * code source holds more than the adapter (for example the host), name
     * the classes here.
     */
    protected Set<Class<?>> adapterClasses() {
        return ProcessWideState.classesOf(adapter().getClass());
    }

    /**
     * The start of every trap value (case 5). The TCK sets the system
     * properties itself. Environment variables cannot be set in a running
     * process, so every environment variable whose value starts with this
     * prefix counts as a trap (the template sets them with
     * {@code environmentVariables} of the Surefire plugin).
     */
    public static final String TRAP_PREFIX = "tck-poison";

    /**
     * A TCK context for a binding of this adapter: {@code configuration} as
     * the values of the connection, {@link #platformConfiguration()} as those
     * of the deployment. The cases themselves take their contexts from
     * {@link #contractHost()}.
     */
    protected final TckAdapterContext context(AdapterDescriptor descriptor, Map<String, String> configuration) {
        TckAdapterContext.Builder builder = TckAdapterContext.builder(descriptor).configuration(configuration)
                .platform(platformConfiguration());
        clientIdentityOf(configuration).ifPresent(builder::clientIdentity);
        return builder.build();
    }

    /**
     * Where the cases take their contexts from. Default:
     * {@link ContractHost#tck()}, which builds a {@link TckAdapterContext}.
     * The suite of an adapter does not override this method. Only the test
     * harness of the Mirador host returns its real context here, so that all
     * cases run against it: host and test class must come from
     * {@link ContractHost#ACCEPTED_PACKAGES}, otherwise every case fails
     * ({@link #theCasesRunAgainstTheTckOrTheHostOfTheRmm()}).
     */
    protected ContractHost contractHost() {
        return ContractHost.tck();
    }

    /**
     * Runs before every test: fails if {@link #contractHost()} is replaced by
     * anything but the test harness of the Mirador host. Another host could
     * hollow out case 4 ({@link ContractHost#ownsThread}) or bend what the
     * context observes. A run against the harness of the host says so in the
     * output.
     */
    @BeforeEach
    public void theCasesRunAgainstTheTckOrTheHostOfTheRmm() {
        ContractHost host = contractHost();
        if (host == ContractHost.tck()) {
            return;
        }
        if (!accepted(host.getClass()) || !accepted(getClass())) {
            throw new TckViolation("contractHost() is replaced by " + host.getClass().getName() + " in "
                    + getClass().getName() + " — only the harness of the Mirador host (packages "
                    + ContractHost.ACCEPTED_PACKAGES + ") runs the cases against another context; the suite of an"
                    + " adapter keeps the default ContractHost.tck()");
        }
        System.out.println("[TCK] Cases run against " + host + " — the harness of the Mirador host, not the suite of an"
                + " adapter");
    }

    private static boolean accepted(Class<?> type) {
        return ContractHost.ACCEPTED_PACKAGES.stream().anyMatch(type.getName()::startsWith);
    }

    /** The context of a connection for a case: from {@link #contractHost()}, with {@link #platformConfiguration()}. */
    private ContractContext contractContext(AdapterDescriptor descriptor, Map<String, String> configuration) {
        Optional<String> identity = clientIdentityOf(configuration);
        return identity.isEmpty() ? contractHost().context(descriptor, configuration, platformConfiguration())
                : contractHost().context(descriptor, configuration, platformConfiguration(), identity);
    }

    /**
     * The client identity of the connection with these values:
     * {@link #secondBindingClientIdentity()} for the values of
     * {@link #secondBindingConfiguration()} if they differ from
     * {@link #configuration()}, otherwise {@link #clientIdentity()}.
     */
    private Optional<String> clientIdentityOf(Map<String, String> configuration) {
        Optional<Map<String, String>> second = secondBindingConfiguration();
        if (second.isPresent() && second.get().equals(configuration) && !configuration.equals(configuration())) {
            Optional<String> ofTheSecond = secondBindingClientIdentity();
            clientIdentity().ifPresent(
                    ofTheFirst -> SecondBindingIdentity.requireAnotherBinding(ofTheFirst, ofTheSecond));
            return ofTheSecond;
        }
        return clientIdentity();
    }

    /**
     * Case 2 across all tests: after every test, {@link #networkTool()} has
     * refused no request that lacked the header of {@code context.http()},
     * including writes, attachments, binding and {@code purge()}. Counted
     * since the start of the tool ({@link FakeHttpTool#refusedSinceStart()}).
     * A failure means the adapter sent with its own HTTP client somewhere in
     * the test. Not checked with {@link #networkOptOut()} or without
     * {@code networkTool()}.
     */
    @AfterEach
    public void noRequestOfTheCaseBypassedTheNetworkPath() {
        if (networkOptOut().isPresent()) {
            return;
        }
        Optional<FakeHttpTool> tool = networkTool();
        if (tool.isPresent() && tool.get().refusedSinceStart() > 0) {
            throw new TckViolation(tool.get().refusedSinceStart() + " request(s) reached networkTool() without the"
                    + " header that only context.http() sets — somewhere in this case (a write, an attachment,"
                    + " bind(), checkHealth() or purge()) the adapter sends with its own HTTP client; every request"
                    + " goes through context.http()");
        }
    }

    // ------------------------------------------------------------------
    // Always: descriptor and binding
    // ------------------------------------------------------------------

    /**
     * {@code descriptor()} is constant (two calls return equal values) and
     * declares the API major version of this TCK. The configuration of an
     * instance (page sizes, paths) belongs in {@code bind()}, not in the
     * descriptor.
     */
    @Test
    public void descriptorIsConstantAndBuiltAgainstThisApiVersion() {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor first = toolAdapter.descriptor();
        AdapterDescriptor second = toolAdapter.descriptor();

        if (!first.equals(second)) {
            throw new TckViolation("descriptor() must be constant — two calls returned different values."
                    + " Instance configuration (page sizes, paths) belongs to bind(), not the descriptor");
        }
        if (first.apiMajorVersion() != AdapterApiVersion.MAJOR) {
            throw new TckViolation("The adapter declares API major version " + first.apiMajorVersion()
                    + ", this TCK belongs to version " + AdapterApiVersion.MAJOR
                    + " — rebuild against the matching rmm_adapter_api");
        }
    }

    /**
     * References that no image reference form may admit: foreign schemes,
     * foreign servers, path traversal, encoded characters and addresses of
     * the host itself.
     */
    private static final List<String> HOSTILE_IMAGE_REFERENCES = List.of(
            "http://evil.example/a.png", "https://evil.example/a.png", "javascript:alert(1)",
            "data:image/png;base64,AAAA", "vbscript:msgbox(1)", "file:///etc/passwd", "blob:https://x/1",
            "//evil.example/a.png", "/../api/a.png", "/a/../../api/a.png", "/%2e%2e/a.png", "/api/a.png",
            "/api/attachment/1/a.png", "/actuator/a.png", "/internal/attachment/1/a.png", "/identity/a.png");

    /**
     * Image reference forms (since contract 1.1): every declared form keeps
     * to the closed grammar of the core and its fixed prohibitions
     * ({@link ImageReferenceForm}), a reference of the form survives one round
     * (once stored, it is again a reference of the same form), and no form
     * admits one of the hostile references. An adapter without image
     * reference forms always passes.
     */
    @Test
    public void declaredImageReferenceFormsKeepToTheGrammarOfTheCore() {
        AdapterDescriptor descriptor;
        try {
            descriptor = adapter().descriptor();
        } catch (IllegalArgumentException outsideTheGrammar) {
            throw new TckViolation("descriptor() fails: " + outsideTheGrammar.getMessage()
                    + " — an image reference form must keep to the grammar of the core"
                    + " (ImageReferenceForm.scheme or ImageReferenceForm.pathTemplate)");
        }
        for (ImageReferenceForm form : descriptor.capabilities().imageReferenceForms()) {
            List<String> problems = ImageReferenceForm.problemsOf(form.kind(), form.value());
            if (!problems.isEmpty()) {
                throw new TckViolation("The image reference form <" + form.value() + "> is outside the grammar of"
                        + " the core: " + String.join("; ", problems));
            }
            String example = form.example();
            Optional<String> stored = form.admit(example);
            if (stored.isEmpty() || !form.admit(stored.get()).equals(stored)) {
                throw new TckViolation("A reference of the image reference form <" + form.value() + "> ("
                        + example + ") does not survive one round: stored as " + stored);
            }
            for (String hostile : HOSTILE_IMAGE_REFERENCES) {
                if (form.admit(hostile).isPresent()) {
                    throw new TckViolation("The image reference form <" + form.value() + "> admits <" + hostile
                            + ">, which every form must refuse");
                }
            }
        }
    }

    /** The upper limit of {@link #parseEventDeadline()}. */
    public static final Duration MAX_PARSE_EVENT_DEADLINE = Duration.ofSeconds(5);

    /** The most ids a parsed event may name; the host treats an event with more as a run over everything. */
    public static final int MAX_EVENT_IDS = 500;

    /**
     * The longest id, after trimming, that a parsed event may name. The host
     * silently drops longer and empty ids; if none is left, the run reads the
     * whole project.
     */
    public static final int MAX_EVENT_ID_LENGTH = 200;

    /** How long the TCK waits at most, after cancelling a call of {@code parseEvent}, for its thread to end. */
    static final Duration PARSE_EVENT_STOP_WAIT = Duration.ofSeconds(1);

    /** The size of the garbage body the TCK feeds to {@code parseEvent}: 64 KiB. */
    static final int GARBAGE_EVENT_BYTES = 64 * 1024;

    /**
     * The deadline of {@code parseEvent} in the TCK. Default and upper limit:
     * {@link #MAX_PARSE_EVENT_DEADLINE}. A test may only shorten it.
     */
    protected Duration parseEventDeadline() {
        return MAX_PARSE_EVENT_DEADLINE;
    }

    /**
     * The event of a webhook: the host calls {@link AdapterInstance#parseEvent}
     * per connection with the body of an unknown caller that has already
     * passed the secret and replay checks. An empty body without headers and
     * 64 KiB of garbage may have only three answers: empty (the general
     * format of the host applies), a read scope that names its ids explicitly
     * (not {@code null}, not empty, at most {@link #MAX_EVENT_IDS}, each
     * non-empty after trimming and at most {@link #MAX_EVENT_ID_LENGTH}
     * characters long), or an {@code AdapterException}. Violations: any other
     * exception (the host then answers 400 although the adapter did not mean
     * to refuse), {@code null}, a full or time-based read scope (any caller
     * could start a full run that way), taking longer than
     * {@link #parseEventDeadline()}, or a request to the tool: parsing reads
     * only what the event carries.
     */
    @Test
    public void parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        Duration deadline = parseEventDeadline();
        if (deadline == null || deadline.isNegative() || deadline.isZero()
                || deadline.compareTo(MAX_PARSE_EVENT_DEADLINE) > 0) {
            throw new TckViolation("parseEventDeadline() is " + deadline + " — parseEvent may take at most "
                    + MAX_PARSE_EVENT_DEADLINE.toSeconds() + " s; a test may shorten it, never lengthen it");
        }
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        ExecutorService worker = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "tck-parse-event");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            byte[] garbage = new byte[GARBAGE_EVENT_BYTES];
            new Random(0x5EED).nextBytes(garbage);
            //Interspersed with characters a JSON reader expects: brackets, quotes, separators
            String json = "{}[]\":,0aZ\n";
            for (int i = 0; i < garbage.length; i += 97) {
                garbage[i] = (byte) json.charAt(i % json.length());
            }
            Map<String, Map<String, List<String>>> headersOf = new LinkedHashMap<>();
            headersOf.put("an empty event", Map.of());
            headersOf.put("64 KiB of garbage", Map.of("content-type", List.of("application/json"),
                    "x-tck-garbage", List.of("\u0000\uFFFF%%%{{{")));
            for (Map.Entry<String, Map<String, List<String>>> event : headersOf.entrySet()) {
                byte[] body = event.getValue().isEmpty() ? new byte[0] : garbage;
                long httpBefore = context.httpRequests();
                int sentBefore = networkTool().map(tool -> tool.requests().size()).orElse(0);
                Future<Optional<ReadScope>> parsed = worker.submit(() -> {
                    try (InputStream in = new ByteArrayInputStream(body)) {
                        return instance.parseEvent(event.getValue(), in);
                    }
                });
                Optional<ReadScope> answer;
                try {
                    answer = parsed.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillWorking) {
                    parsed.cancel(true);
                    throw new TckViolation("parseEvent(...) of " + event.getKey() + " took longer than "
                            + deadline.toMillis() + " ms — it reads only the event (headers and body), never waits"
                            + " for the tool");
                } catch (ExecutionException failed) {
                    if (failed.getCause() instanceof AdapterException) {
                        answer = Optional.empty();
                    } else {
                        TckViolation violation = new TckViolation("parseEvent(...) of " + event.getKey() + " threw "
                                + failed.getCause() + " — an event it cannot read is Optional.empty() or an"
                                + " AdapterException; the host answers every other exception with 400");
                        violation.initCause(failed.getCause());
                        throw violation;
                    }
                }
                if (answer == null) {
                    throw new TckViolation("parseEvent(...) of " + event.getKey() + " returned null — Optional.empty()"
                            + " means: not the tool's format, the host's general format applies");
                }
                if (answer.isPresent()) {
                    ReadScope scope = answer.get();
                    if (!scope.isExplicit() || scope.elementIds() == null || scope.elementIds().isEmpty()
                            || scope.elementIds().size() > MAX_EVENT_IDS
                            || scope.elementIds().stream().anyMatch(id -> id == null || id.trim().isEmpty()
                            || id.trim().length() > MAX_EVENT_ID_LENGTH)) {
                        throw new TckViolation("parseEvent(...) of " + event.getKey() + " returned " + scope
                                + " — an event names its elements (ReadScope.ofIds with 1 to " + MAX_EVENT_IDS
                                + " ids, each 1 to " + MAX_EVENT_ID_LENGTH + " characters after trimming; the host"
                                + " drops any other id); a full or time-based read would let any caller start a full"
                                + " run");
                    }
                }
                long requests = context.httpRequests() - httpBefore;
                int sent = networkTool().map(tool -> tool.requests().size()).orElse(0) - sentBefore;
                if (requests > 0 || sent > 0) {
                    throw new TckViolation("parseEvent(...) of " + event.getKey() + " sent "
                            + Math.max(requests, sent) + " request(s) to the tool — it reads only the event; the run"
                            + " that follows reads the tool");
                }
            }
        } finally {
            try {
                worker.shutdownNow();
                //A call that ignores the interrupt would otherwise keep running while the instance closes
                worker.awaitTermination(PARSE_EVENT_STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                BindingLifeCycle.close(instance, context);
            }
        }
    }

    /**
     * Identifier kinds: every kind passes its round trip, that is, what its
     * format writes, its pattern reads as exactly that identifier. Otherwise
     * every run would see a changed field. Checked for the kinds of the
     * descriptor and, after binding, for those of the binding if it declares
     * its own (a remote type declares none, its service does).
     */
    @Test
    public void identifierKindsRoundTrip() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        requireRoundTrips(descriptor.identifierKinds());
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        if (instance == null) {
            context.release();
            throw new TckViolation("bind(...) returned null");
        }
        try {
            requireRoundTrips(BoundInstance.identifierKindsOf(descriptor, instance));
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    private static void requireRoundTrips(List<IdentifierKind> kinds) {
        for (IdentifierKind kind : kinds) {
            if (kind == null) {
                throw new TckViolation("An identifier kind is null");
            }
            String failure = kind.verifyRoundTrip();
            if (failure != null) {
                throw new TckViolation(failure.substring(0, 1).toUpperCase(Locale.ROOT) + failure.substring(1));
            }
        }
    }

    /**
     * What a binding declares beyond its type, the host accepts only by its
     * rule ({@link TckBindingDeclaration}): capabilities only narrowed, only
     * permitted keys, identifier kinds without {@code null}, without a
     * duplicate name and within their limits; none of the three {@code null}.
     * Otherwise the host holds the connection as
     * {@code DEFECT INSTANCE_INCONSISTENT}, so an adapter that passes here
     * also binds in the host. An adapter whose bindings declare nothing of
     * their own always passes.
     */
    @Test
    public void theBindingDeclarationOnlyNarrowsItsType() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = toolAdapter.bind(context);
        if (instance == null) {
            context.release();
            throw new TckViolation("bind(...) returned null");
        }
        try {
            List<String> refused = TckBindingDeclaration.refusals(descriptor, instance);
            if (!refused.isEmpty()) {
                throw new TckViolation("The bound instance declares what the host does not accept (it holds the"
                        + " connection DEFECT INSTANCE_INCONSISTENT; a binding may only narrow its descriptor): "
                        + String.join("; ", refused));
            }
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    /** No configuration key of the descriptor is declared twice. */
    @Test
    public void configKeysAreDeclaredWithoutDuplicates() {
        Set<String> seen = new HashSet<>();
        for (ConfigKey key : adapter().descriptor().configKeys()) {
            if (!seen.add(key.key())) {
                throw new TckViolation("Configuration key <" + key.key() + "> is declared twice");
            }
        }
    }

    /**
     * {@code bind()} returns an instance that keeps what its capabilities
     * declare (its own, otherwise those of the descriptor): a
     * {@code SourceReader} for {@code TO_RMM}, a {@code TargetWriter} for
     * {@code FROM_RMM}, a {@code pathHierarchy()} for {@code PATH_ATTRIBUTE},
     * and an overridden hook behind every declared phase, bulk operation and
     * {@code supportsArtifactTypeChange}. A declaration that still has the
     * default of the API behind it is an empty promise. Fails too if
     * {@code bind()} returns {@code null}.
     */
    @Test
    public void bindYieldsAnInstanceConsistentWithTheCapabilities() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = toolAdapter.bind(context);
        if (instance == null) {
            context.release();
            throw new TckViolation("bind(...) returned null");
        }
        try {
            BindingLifeCycle.declareTheKeysOfTheBinding(context, instance);
            //The capabilities of the binding: for example a read-only service under a type with both
            //directions, or one with a path attribute under a type with a tree
            AdapterCapabilities capabilities = BoundInstance.capabilitiesOf(descriptor, instance);
            if (capabilities.directions().contains(SyncDirection.TO_RMM)
                    && instance.reader().isEmpty()) {
                throw new TckViolation("Capabilities declare TO_RMM, but the instance provides no SourceReader");
            }
            if (capabilities.directions().contains(SyncDirection.FROM_RMM)
                    && instance.writer().isEmpty()) {
                throw new TckViolation("Capabilities declare FROM_RMM, but the instance provides no TargetWriter");
            }
            if (capabilities.hierarchyModel() == HierarchyModel.PATH_ATTRIBUTE
                    && instance.pathHierarchy().isEmpty()) {
                throw new TckViolation("HierarchyModel PATH_ATTRIBUTE requires the bound instance"
                        + " to provide its configured pathHierarchy()");
            }
            DeclaredHooks.checkPhases(capabilities, instance);
            DeclaredHooks.checkBulkOperations(capabilities, instance);
            DeclaredHooks.checkTypeChange(capabilities, instance);
        } finally {
            BindingLifeCycle.close(instance, context);
        }
    }

    // ------------------------------------------------------------------
    // Against the adapter's own tool (opt-in): read contract and round trip
    // ------------------------------------------------------------------

    /**
     * Every declared read phase (artifacts, then relationships, comments and
     * attachments) terminates within {@link #maxPages()} pages and keeps the
     * cursor rules of {@link ReadContract}: no {@code null} page, no cursor
     * value twice. Skipped without {@link #liveRunContext()} or without the
     * direction {@code TO_RMM}.
     */
    @Test
    public void everyReadPhaseTerminatesWithSaneCursors() throws Exception {
        assumeTrue(liveRunContext().isPresent(),
                "liveRunContext() is missing — read checks against the tool skipped");
        assumeTrue(adapter().descriptor().capabilities().directions().contains(SyncDirection.TO_RMM),
                "The adapter does not declare the direction TO_RMM");
        SyncRunContext runContext = liveRunContext().orElseThrow();
        ToolAdapter toolAdapter = adapter();
        ContractContext context = contractContext(toolAdapter.descriptor(), configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            SourceReader reader = BoundInstance.readerOf(instance);
            //Readers may keep state per run (runStore()). The order of the
            //sync core: artifacts, then relationships, then comments, and
            //only the declared phases
            AdapterCapabilities capabilities = BoundInstance.capabilitiesOf(toolAdapter.descriptor(), instance);
            context.inRun(runContext, () -> {
                ReadContract.readAll(context.paging(), "readArtifacts", reader::readArtifacts, runContext,
                        ReadScope.full(), maxPages());
                if (capabilities.reads(SyncPhase.RELATIONSHIPS)) {
                    ReadContract.readAll(context.paging(), "readRelationships", reader::readRelationships, runContext,
                            ReadScope.full(), maxPages());
                }
                if (capabilities.reads(SyncPhase.COMMENTS)) {
                    ReadContract.readAll(context.paging(), "readComments", reader::readComments, runContext,
                            ReadScope.full(), maxPages());
                }
                if (capabilities.reads(SyncPhase.ATTACHMENTS)) {
                    ReadContract.readAll(context.paging(), "readAttachments", reader::readAttachments, runContext,
                            ReadScope.full(), maxPages());
                }
                return null;
            });
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    /**
     * The scope "these ids": every adapter delivers the named elements as far
     * as the tool has them. No capability is needed, because every tool can
     * read by id. A superset is allowed, an omission is not. Skipped without
     * {@link #liveRunContext()}, without the direction {@code TO_RMM} or for
     * a test project without elements.
     */
    @Test
    public void anExplicitIdScopeDeliversTheNamedElements() throws Exception {
        assumeTrue(liveRunContext().isPresent(),
                "liveRunContext() is missing — id scope check against the tool skipped");
        assumeTrue(adapter().descriptor().capabilities().directions().contains(SyncDirection.TO_RMM),
                "The adapter does not declare the direction TO_RMM");
        SyncRunContext runContext = liveRunContext().orElseThrow();
        ToolAdapter toolAdapter = adapter();
        ContractContext context = contractContext(toolAdapter.descriptor(), configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            SourceReader reader = BoundInstance.readerOf(instance);
            List<String> all = context.inRun(runContext, () -> ReadContract.readAll(context.paging(),
                    "readArtifacts", reader::readArtifacts, runContext, ReadScope.full(), maxPages()))
                    .elements().stream().map(ElementArtifact::getIdValue)
                    .filter(Objects::nonNull).distinct().toList();
            assumeTrue(!all.isEmpty(), "The test project has no elements — id scope check skipped");
            Set<String> named = new LinkedHashSet<>(all.subList(0, Math.min(2, all.size())));
            Set<String> delivered = new HashSet<>();
            //A read of "these ids" is a run of its own (for example after an event of the tool)
            context.inRun(runContext, () -> ReadContract.readAll(context.paging(), "readArtifacts(ids)",
                    reader::readArtifacts, runContext, ReadScope.ofIds(named), maxPages())).elements()
                    .forEach(element -> delivered.add(element.getIdValue()));
            assertTrue(delivered.containsAll(named),
                    "named elements that the tool has must be delivered: named " + named + ", delivered "
                            + delivered);
            assertTrue(delivered.size() <= all.size(), "at most the inventory of the project");
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    /**
     * An adapter that declares {@code supportsDeltaRead} reads the artifacts
     * with a delta scope that starts now, and the read terminates. Skipped
     * without {@link #liveRunContext()} or without the capability.
     */
    @Test
    public void aDeclaredDeltaReadAcceptsADeltaScope() throws Exception {
        assumeTrue(liveRunContext().isPresent(),
                "liveRunContext() is missing — delta check against the tool skipped");
        assumeTrue(adapter().descriptor().capabilities().supportsDeltaRead(),
                "The adapter does not declare supportsDeltaRead");
        SyncRunContext runContext = liveRunContext().orElseThrow();
        ToolAdapter toolAdapter = adapter();
        ContractContext context = contractContext(toolAdapter.descriptor(), configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            //A delta from now on must terminate: the weakest statement that
            //always holds without knowledge of the test data
            context.inRun(runContext, () -> ReadContract.readAll(context.paging(), "readArtifacts(delta)",
                    BoundInstance.readerOf(instance, "supportsDeltaRead")::readArtifacts, runContext,
                    ReadScope.since(Instant.now()), maxPages()));
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    /**
     * The round trip of an artifact: {@code createArtifact} returns the id the
     * tool assigned, {@code readArtifacts} delivers the artifact under that
     * id, {@code updateArtifact} returns a receipt that reports the accepted
     * fields and names only fields it was given, and the artifact is deleted
     * again. Skipped without {@link #liveRunContext()} or
     * {@link #roundTripArtifact()}, or unless the adapter declares both
     * directions.
     */
    @Test
    public void roundTripCreatesFindsAndDeletesAnArtifact() throws Exception {
        assumeTrue(liveRunContext().isPresent(),
                "liveRunContext() is missing — round trip skipped");
        assumeTrue(roundTripArtifact().isPresent(),
                "roundTripArtifact() is missing — round trip skipped");
        AdapterDescriptor descriptor = adapter().descriptor();
        assumeTrue(descriptor.capabilities().directions()
                        .containsAll(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM)),
                "The round trip needs both sync directions");
        SyncRunContext runContext = liveRunContext().orElseThrow();
        ToolAdapter toolAdapter = adapter();
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            context.inRun(runContext, () -> roundTrip(instance, runContext));
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    /** The round trip of an artifact within one run. */
    private Void roundTrip(AdapterInstance instance, SyncRunContext runContext) throws Exception {
        TargetWriter writer = BoundInstance.writerOf(instance);
        SourceReader reader = BoundInstance.readerOf(instance);
        String externalId = writer.createArtifact(runContext, roundTripArtifact().orElseThrow());
        if (externalId == null || externalId.isBlank()) {
            throw new TckViolation("createArtifact must return the id the tool assigned —"
                    + " Mirador stores it as the element's external reference");
        }
        try {
            boolean found = ReadContract.readAll("readArtifacts", reader::readArtifacts,
                            runContext, ReadScope.full(), maxPages())
                    .elements().stream()
                    .anyMatch(artifact -> externalId.equals(artifact.getIdValue()));
            if (!found) {
                throw new TckViolation("The artifact created as <" + externalId
                        + "> was not delivered by readArtifacts — created elements must be readable,"
                        + " otherwise the next run would create them again");
            }
            //The write receipt: updateArtifact says which fields the tool
            //accepted; the sync core acknowledges only confirmed fields
            ElementArtifact update = roundTripArtifact().orElseThrow();
            update.setId(new ElementId(externalId));
            TargetOutcome receipt = writer.updateArtifact(runContext, update);
            if (receipt == null) {
                throw new TckViolation("updateArtifact must return a TargetOutcome — Mirador reads the"
                        + " receipt of the fields the tool accepted from it");
            }
            if (!receipt.reportsFields()) {
                throw new TckViolation("updateArtifact must report the fields the tool accepted"
                        + " (TargetOutcome.applied) — without the receipt Mirador treats every field as"
                        + " unwritten and retries it on each run");
            }
            Set<String> sent = new HashSet<>();
            if (update.getFields() != null) {
                update.getFields().values().forEach(field -> sent.add(field.getLabelValue()));
            }
            if (!sent.containsAll(receipt.appliedFields())) {
                throw new TckViolation("updateArtifact reported fields it was never given: "
                        + receipt.appliedFields() + " (sent " + sent + ")");
            }
            WriteOutcomes.requireWritten("updateArtifact", receipt, update);
        } finally {
            //Cleaning up is part of the round trip: the test project stays clean
            writer.deleteArtifact(runContext, externalId);
        }
        return null;
    }

    /**
     * The round trip of a comment: create an artifact, create
     * {@link #roundTripComment()} at it ({@code createCommentWithOutcome}, as
     * the sync core does), read in the phase order of the sync core (the
     * artifacts, then the comments) and find the comment under its id exactly
     * once, at the same artifact and with its text. A comment at another or
     * no element would hang at the wrong element in Mirador or be lost; two
     * comments with the same id would merge into one. The writer must not
     * skip the comment: the template is one the tool can show. Afterwards the
     * artifact is deleted; the contract has no hook that deletes a comment,
     * the artifact takes it along in the tool.
     *
     * <p>The TCK writes a copy of the template, with the text in the field
     * {@link AdapterConventions#COMMENT_BODY_FIELD}, and reads it, as the
     * sync core does, in the field the binding declares with
     * {@code metadata().commentFields()} (otherwise the same field). The text
     * counts as the same if it is equal without markup (tags) and with
     * collapsed whitespace, because a tool stores it in its own markup.</p>
     *
     * <p>Skipped without {@link #roundTripComment()},
     * {@link #liveRunContext()} or {@link #roundTripArtifact()}, or unless
     * adapter and binding declare the comment phase in both directions.</p>
     */
    @Test
    public void commentRoundTripCreatesAndFindsTheCommentAtItsArtifact() throws Exception {
        Optional<ElementComment> template = roundTripComment();
        assumeTrue(template != null && template.isPresent(),
                "roundTripComment() is missing — comment round trip skipped");
        AdapterCapabilities capabilities = adapter().descriptor().capabilities();
        assumeTrue(capabilities.writes(SyncPhase.COMMENTS) && capabilities.reads(SyncPhase.COMMENTS),
                "The adapter does not declare the comment phase in both directions");
        requireLiveRoundTrip();
        SyncRunContext runContext = liveRunContext().orElseThrow();
        Live live = liveInstance(runContext);
        AdapterInstance instance = live.instance();
        try {
            AdapterCapabilities ofTheBinding = BoundInstance.capabilitiesOf(adapter().descriptor(), instance);
            assumeTrue(ofTheBinding.writes(SyncPhase.COMMENTS) && ofTheBinding.reads(SyncPhase.COMMENTS),
                    "The binding does not declare the comment phase in both directions");
            TargetWriter writer = BoundInstance.writerOf(instance);
            SourceReader reader = BoundInstance.readerOf(instance);
            String parentId = writer.createArtifact(runContext, roundTripArtifact().orElseThrow());
            try {
                //A copy: the template of the adapter stays as it is
                ElementComment comment = RoundTripComment.copyOf(template.orElseThrow());
                String written = RoundTripComment.requireText(comment);
                comment.setCommentToItemId(new ElementId(parentId));
                String commentId = RoundTripComment.requireCreated(
                        writer.createCommentWithOutcome(runContext, comment));
                //The order of the sync core: the artifacts first, then the comments
                ReadContract.readAll(live.context().paging(), "readArtifacts", reader::readArtifacts, runContext,
                        ReadScope.full(), maxPages());
                List<ElementComment> delivered = ReadContract.readAll(live.context().paging(), "readComments",
                                reader::readComments, runContext, ReadScope.full(), maxPages())
                        .elements().stream()
                        .filter(read -> commentId.equals(read.getIdValue()))
                        .toList();
                RoundTripComment.requireReadBack(delivered, commentId, parentId, written, instance);
            } finally {
                writer.deleteArtifact(runContext, parentId);
            }
        } finally {
            live.close();
        }
    }

    /**
     * Variability: feature trees and variants travel as a whole from Mirador
     * to the tool, the selection per element in both directions. The round
     * trip creates an artifact, writes a tree, a variant and a selection,
     * reads the selection back, clears it and reads again: an empty selection
     * is a statement, not a gap. Afterwards it deletes the artifact and writes
     * the complete state without trees and variants, which removes whatever
     * the test project mirrored before: a productively mirrored project is no
     * test project. Skipped without {@link #liveRunContext()} or
     * {@link #roundTripArtifact()}, or unless the adapter declares the
     * variability phase in both directions.
     */
    @Test
    public void variabilityRoundTripMirrorsTheSelectionOfAnElement() throws Exception {
        assumeTrue(liveRunContext().isPresent(),
                "liveRunContext() is missing — variability round trip skipped");
        assumeTrue(roundTripArtifact().isPresent(),
                "roundTripArtifact() is missing — variability round trip skipped");
        AdapterDescriptor descriptor = adapter().descriptor();
        assumeTrue(descriptor.capabilities().writes(SyncPhase.VARIABILITY)
                        && descriptor.capabilities().reads(SyncPhase.VARIABILITY),
                "The adapter does not declare the variability phase in both directions");
        SyncRunContext runContext = liveRunContext().orElseThrow();
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = BindingLifeCycle.bind(adapter(), context);
        try {
            context.inRun(runContext,
                    () -> VariabilityRoundTrip.run(instance, runContext, this::roundTripArtifact, this::maxPages));
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    // ------------------------------------------------------------------
    // Behavior tests against the adapter's own tool (opt-in like the round trip)
    // ------------------------------------------------------------------

    /** The preconditions of the behavior tests; skipped otherwise. */
    private void requireLiveRoundTrip() {
        assumeTrue(liveRunContext().isPresent(),
                "liveRunContext() is missing — behavior test skipped");
        assumeTrue(roundTripArtifact().isPresent(),
                "roundTripArtifact() is missing — behavior test skipped");
        assumeTrue(adapter().descriptor().capabilities().directions()
                        .containsAll(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM)),
                "The behavior tests need both sync directions");
    }

    /**
     * Binds for a behavior test and then starts the run {@code runContext} on
     * this thread, as the host does: binding happens outside a run, every
     * hook call inside one.
     */
    private Live liveInstance(SyncRunContext runContext) throws Exception {
        requireLiveRoundTrip();
        return live(adapter(), configuration(), runContext);
    }

    /** Binds {@code configuration} and then starts the run {@code runContext} on this thread. */
    private Live live(ToolAdapter toolAdapter, Map<String, String> configuration, SyncRunContext runContext)
            throws Exception {
        ContractContext context = contractContext(toolAdapter.descriptor(), configuration);
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            return new Live(context, instance, context.startRun(runContext));
        } catch (RuntimeException failed) {
            BindingLifeCycle.closeAndRelease(instance, context);
            throw failed;
        }
    }

    /**
     * A binding inside the run of a test. {@link #close()} ends the run
     * (which empties its run store), closes the binding and releases what the
     * host releases afterwards.
     */
    private record Live(ContractContext context, AdapterInstance instance, ContractContext.Run run)
            implements AutoCloseable {

        @Override
        public void close() {
            try {
                run.close();
            } finally {
                BindingLifeCycle.closeAndRelease(instance, context);
            }
        }
    }

    /**
     * The declared artifact bulk hooks keep the batch contract: exactly one
     * outcome per element, in input order, so that a failure hits only its
     * own element; every created element carries the id the tool assigned,
     * no id twice, and is readable afterwards; an update outcome reports the
     * accepted fields. No outcome is "skipped", and fields reported as not
     * written are fields that were sent. Relationship and comment batches
     * need knowledge of the tool and are left to the tests of the adapter.
     * Skipped unless the adapter declares {@code ARTIFACT_CREATE},
     * {@code ARTIFACT_UPDATE} or {@code ARTIFACT_DELETE}, and without
     * {@link #liveRunContext()}, {@link #roundTripArtifact()} or both
     * directions.
     */
    @Test
    public void declaredArtifactBulkHooksAnswerOneOutcomePerElementInOrder() throws Exception {
        Set<BulkOperation> declared = adapter().descriptor().capabilities().bulkOperations();
        assumeTrue(declared.contains(BulkOperation.ARTIFACT_CREATE)
                        || declared.contains(BulkOperation.ARTIFACT_UPDATE)
                        || declared.contains(BulkOperation.ARTIFACT_DELETE),
                "no artifact bulk hook declared — batch contract skipped");
        requireLiveRoundTrip();
        SyncRunContext runContext = liveRunContext().orElseThrow();
        Live live = liveInstance(runContext);
        try {
            AdapterInstance instance = live.instance();
            //The binding may declare fewer bulk operations than its type
            declared = BoundInstance.capabilitiesOf(adapter().descriptor(), instance).bulkOperations();
            ArtifactBatches.require(instance, declared, runContext, this::roundTripArtifact, this::maxPages);
        } finally {
            live.close();
        }
    }

    /**
     * {@code supportsPartialUpdate} promises that an update carrying only
     * some of the fields leaves the others untouched in the tool; the sync
     * core then sends only the changed attributes. A tool that clears the
     * fields it was not sent must not declare the capability.
     *
     * <p>Like the write payload of the sync core, the partial update carries
     * name, label and location of the round-trip artifact and one of its
     * fields: the core shortens only the attributes, never type and location.
     * An adapter that declares the capability must return a
     * {@link #roundTripArtifact()} with at least two fields; with fewer the
     * test fails instead of being skipped, so that a declared capability
     * never goes unchecked. Skipped without the capability,
     * {@link #liveRunContext()}, {@code roundTripArtifact()} or both
     * directions.</p>
     */
    @Test
    public void aDeclaredPartialUpdateLeavesTheOtherFieldsUntouched() throws Exception {
        assumeTrue(adapter().descriptor().capabilities().supportsPartialUpdate(),
                "supportsPartialUpdate not declared — partial update contract skipped");
        requireLiveRoundTrip();
        ElementArtifact full = roundTripArtifact().orElseThrow();
        if (full.getFields() == null || full.getFields().size() < 2) {
            throw new TckViolation("The adapter declares supportsPartialUpdate, but roundTripArtifact() carries "
                    + (full.getFields() == null ? 0 : full.getFields().size())
                    + " field(s): the partial-update contract needs at least two (one sent, one left untouched)"
                    + " — add a second field to roundTripArtifact() or do not declare supportsPartialUpdate");
        }
        SyncRunContext runContext = liveRunContext().orElseThrow();
        Live live = liveInstance(runContext);
        AdapterInstance instance = live.instance();
        try {
            TargetWriter writer = BoundInstance.writerOf(instance);
            SourceReader reader = BoundInstance.readerOf(instance);
            String externalId = writer.createArtifact(runContext, full);
            try {
                Iterator<ElementField<?>> fields = roundTripArtifact().orElseThrow().getFields().values().iterator();
                ElementField<?> sent = fields.next();
                //Mirrors the write payload of the sync core: it always sends name, label
                //(the tool's type) and location and shortens only the attributes
                ElementArtifact template = roundTripArtifact().orElseThrow();
                ElementArtifact partial = new ElementArtifact();
                partial.setId(new ElementId(externalId));
                partial.setName(template.getName());
                partial.setLabel(template.getLabel());
                partial.setLocation(template.getLocation());
                partial.addField(sent);
                TargetOutcome receipt = writer.updateArtifact(runContext, partial);
                if (receipt == null || !receipt.reportsFields()) {
                    throw new TckViolation("updateArtifact must report the accepted fields (TargetOutcome.applied)");
                }
                WriteOutcomes.requireWritten("updateArtifact (partial)", receipt, partial);
                ElementArtifact readBack = ReadContract.readAll("readArtifacts", reader::readArtifacts,
                                runContext, ReadScope.full(), maxPages())
                        .elements().stream()
                        .filter(artifact -> externalId.equals(artifact.getIdValue()))
                        .findFirst()
                        .orElseThrow(() -> new TckViolation("The artifact <" + externalId
                                + "> was not delivered by readArtifacts after the partial update"));
                while (fields.hasNext()) {
                    ElementField<?> untouched = fields.next();
                    ElementField<?> actual = readBack.getFieldByLabel(untouched.getLabelValue());
                    if (actual == null || !Objects.equals(actual.getValueAsString(),
                            untouched.getValueAsString())) {
                        throw new TckViolation("supportsPartialUpdate promises that fields not sent stay"
                                + " untouched, but <" + untouched.getLabelValue() + "> "
                                + (actual == null ? "vanished" : "changed to <" + actual.getValueAsString() + ">")
                                + " after an update carrying only <" + sent.getLabelValue() + ">");
                    }
                }
            } finally {
                writer.deleteArtifact(runContext, externalId);
            }
        } finally {
            live.close();
        }
    }

    /**
     * Update and delete are idempotent: the same update twice succeeds with
     * the same receipt, and deleting an element that is already gone is no
     * error. The host relies on it: it repeats both hooks after a timeout,
     * and a delete that fails with "not found" the second time would never be
     * acknowledged. Skipped without {@link #liveRunContext()},
     * {@link #roundTripArtifact()} or both directions.
     */
    @Test
    public void updateAndDeleteAreIdempotent() throws Exception {
        requireLiveRoundTrip();
        SyncRunContext runContext = liveRunContext().orElseThrow();
        Live live = liveInstance(runContext);
        AdapterInstance instance = live.instance();
        try {
            TargetWriter writer = BoundInstance.writerOf(instance);
            SourceReader reader = BoundInstance.readerOf(instance);
            String externalId = writer.createArtifact(runContext, roundTripArtifact().orElseThrow());
            ElementArtifact update = roundTripArtifact().orElseThrow();
            update.setId(new ElementId(externalId));
            TargetOutcome first = writer.updateArtifact(runContext, update);
            ElementArtifact again = roundTripArtifact().orElseThrow();
            again.setId(new ElementId(externalId));
            TargetOutcome second = writer.updateArtifact(runContext, again);
            if (first == null || second == null || !first.isSuccess() || !second.isSuccess()) {
                throw new TckViolation("The same update must succeed when repeated");
            }
            WriteOutcomes.requireWritten("updateArtifact", first, update);
            WriteOutcomes.requireWritten("updateArtifact (repeated)", second, again);
            if (!Objects.equals(first.appliedFields(), second.appliedFields())) {
                throw new TckViolation("The same update must report the same receipt when repeated: "
                        + first.appliedFields() + " vs " + second.appliedFields());
            }
            writer.deleteArtifact(runContext, externalId);
            try {
                writer.deleteArtifact(runContext, externalId);
            } catch (Exception e) {
                throw new TckViolation("Deleting an element that is already gone must not fail — the host"
                        + " repeats deletes after a timeout: " + e.getMessage());
            }
            boolean stillThere = ReadContract.readAll("readArtifacts", reader::readArtifacts, runContext,
                            ReadScope.full(), maxPages())
                    .elements().stream().anyMatch(artifact -> externalId.equals(artifact.getIdValue()));
            if (stillThere) {
                throw new TckViolation("The artifact <" + externalId + "> is still readable after deletion");
            }
        } finally {
            live.close();
        }
    }

    /**
     * The host writes batches in parallel: updates of the same element from
     * several threads must neither fail nor damage the element. Every thread
     * works inside the run of the test ({@link ContractContext.Run#within}),
     * like the parallel tasks of a run in the host. Skipped without
     * {@link #liveRunContext()}, {@link #roundTripArtifact()} or both
     * directions.
     */
    @Test
    public void parallelUpdatesOfOneElementAreSafe() throws Exception {
        requireLiveRoundTrip();
        SyncRunContext runContext = liveRunContext().orElseThrow();
        Live live = liveInstance(runContext);
        AdapterInstance instance = live.instance();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            TargetWriter writer = BoundInstance.writerOf(instance);
            SourceReader reader = BoundInstance.readerOf(instance);
            String externalId = writer.createArtifact(runContext, roundTripArtifact().orElseThrow());
            try {
                List<Future<TargetOutcome>> results = new ArrayList<>();
                for (int thread = 0; thread < 4; thread++) {
                    results.add(pool.submit(() -> live.run().within(() -> {
                        TargetOutcome last = null;
                        for (int round = 0; round < 5; round++) {
                            ElementArtifact update = roundTripArtifact().orElseThrow();
                            update.setId(new ElementId(externalId));
                            last = writer.updateArtifact(runContext, update);
                        }
                        return last;
                    })));
                }
                for (Future<TargetOutcome> result : results) {
                    TargetOutcome outcome;
                    try {
                        outcome = result.get();
                    } catch (ExecutionException e) {
                        throw new TckViolation("A parallel update failed: " + e.getCause());
                    }
                    if (outcome == null || !outcome.isSuccess()) {
                        throw new TckViolation("A parallel update reported failure: "
                                + (outcome == null ? "null" : String.valueOf(outcome.failure())));
                    }
                    WriteOutcomes.requireWritten("updateArtifact (parallel)", outcome, null);
                }
                ElementArtifact readBack = ReadContract.readAll("readArtifacts", reader::readArtifacts,
                                runContext, ReadScope.full(), maxPages())
                        .elements().stream().filter(artifact -> externalId.equals(artifact.getIdValue()))
                        .findFirst().orElseThrow(() -> new TckViolation("The artifact <" + externalId
                                + "> vanished under parallel updates"));
                ElementArtifact expected = roundTripArtifact().orElseThrow();
                if (expected.getFields() != null) {
                    for (ElementField<?> field : expected.getFields().values()) {
                        ElementField<?> actual = readBack.getFieldByLabel(field.getLabelValue());
                        if (actual == null || !Objects.equals(actual.getValueAsString(),
                                field.getValueAsString())) {
                            throw new TckViolation("Field <" + field.getLabelValue()
                                    + "> is damaged after parallel updates: "
                                    + (actual == null ? "missing" : actual.getValueAsString()));
                        }
                    }
                }
            } finally {
                writer.deleteArtifact(runContext, externalId);
            }
        } finally {
            pool.shutdownNow();
            live.close();
        }
    }

    /**
     * Without {@code supportsRelationshipUpdate} the sync core changes a link
     * as delete plus create, and the tool must carry that path: the old link
     * disappears from the read, the new one appears under its own id. With
     * the capability, {@code updateRelationship} must keep the link alive and
     * return its id. Read in the phase order of the sync core: in every run
     * the artifacts first, then the relationships. Skipped without
     * {@link #roundTripRelationshipType()}, {@link #liveRunContext()} or
     * {@link #roundTripArtifact()}, or unless the adapter declares the
     * relationship phase in both directions.
     */
    @Test
    public void relationshipChangesFollowTheDeclaredUpdatePath() throws Exception {
        assumeTrue(roundTripRelationshipType().isPresent(),
                "roundTripRelationshipType() is missing — relationship behavior test skipped");
        assumeTrue(adapter().descriptor().capabilities().writes(SyncPhase.RELATIONSHIPS)
                        && adapter().descriptor().capabilities().reads(SyncPhase.RELATIONSHIPS),
                "The adapter does not declare the relationship phase in both directions");
        requireLiveRoundTrip();
        SyncRunContext runContext = liveRunContext().orElseThrow();
        Live live = liveInstance(runContext);
        AdapterInstance instance = live.instance();
        try {
            boolean updates = BoundInstance.capabilitiesOf(adapter().descriptor(), instance)
                    .supportsRelationshipUpdate();
            TargetWriter writer = BoundInstance.writerOf(instance);
            SourceReader reader = BoundInstance.readerOf(instance);
            String from = writer.createArtifact(runContext, roundTripArtifact().orElseThrow());
            String to = writer.createArtifact(runContext, roundTripArtifact().orElseThrow());
            String linkId = null;
            try {
                ElementRelationship link = new ElementRelationship(null,
                        new ElementName(roundTripRelationshipType().orElseThrow()),
                        new ElementId(from), new ElementId(to));
                linkId = writer.createRelationship(runContext, link);
                if (linkId == null || linkId.isBlank()) {
                    throw new TckViolation("createRelationship must return the id the tool assigned");
                }
                String finalLinkId = linkId;
                if (updates) {
                    ElementRelationship changed = new ElementRelationship(new ElementId(linkId),
                            new ElementName(roundTripRelationshipType().orElseThrow()),
                            new ElementId(to), new ElementId(from));
                    String updatedId = writer.updateRelationship(runContext, changed);
                    if (updatedId == null || updatedId.isBlank()) {
                        throw new TckViolation("updateRelationship must return the link id");
                    }
                    linkId = updatedId;
                } else {
                    writer.deleteRelationship(runContext, linkId);
                    ElementRelationship recreated = new ElementRelationship(null,
                            new ElementName(roundTripRelationshipType().orElseThrow()),
                            new ElementId(to), new ElementId(from));
                    linkId = writer.createRelationship(runContext, recreated);
                    //The order of the sync core: the artifacts first, then the
                    //relationships. An adapter that collects the links while
                    //it reads the artifacts has its data set afterwards
                    ReadContract.readAll(live.context().paging(), "readArtifacts", reader::readArtifacts, runContext,
                            ReadScope.full(), maxPages());
                    Set<String> readable = new HashSet<>();
                    ReadContract.readAll("readRelationships", reader::readRelationships, runContext,
                            ReadScope.full(), maxPages()).elements()
                            .forEach(relationship -> readable.add(relationship.getIdValue()));
                    if (readable.contains(finalLinkId)) {
                        throw new TckViolation("The deleted link <" + finalLinkId
                                + "> is still readable — delete+create would leave duplicates");
                    }
                    if (!readable.contains(linkId)) {
                        throw new TckViolation("The recreated link <" + linkId + "> is not readable");
                    }
                }
            } finally {
                if (linkId != null) {
                    writer.deleteRelationship(runContext, linkId);
                }
                writer.deleteArtifact(runContext, from);
                writer.deleteArtifact(runContext, to);
            }
        } finally {
            live.close();
        }
    }

    /**
     * The metadata of a tool is the basis of the mapping check: every list is
     * present, field types have a unique, non-blank id and a label, field
     * types with options have a data type, and artifact types have a unique,
     * non-blank id. Skipped without {@link #liveRunContext()} or if the
     * instance provides no {@code MetadataProvider}.
     */
    @Test
    public void metadataDescribesTheToolConsistently() throws Exception {
        assumeTrue(liveRunContext().isPresent(), "liveRunContext() is missing — metadata check skipped");
        ContractContext context = contractContext(adapter().descriptor(), configuration());
        AdapterInstance instance = BindingLifeCycle.bind(adapter(), context);
        try {
            assumeTrue(instance.metadata().isPresent(), "no MetadataProvider — metadata check skipped");
            MetadataProvider metadata = instance.metadata().orElseThrow();
            String project = liveRunContext().orElseThrow().externalProjectId();
            var artifactTypes = metadata.artifactTypes(project);
            var fieldTypes = metadata.fieldTypes(project);
            var relationshipTypes = metadata.relationshipTypes(project);
            if (artifactTypes == null || fieldTypes == null || relationshipTypes == null) {
                throw new TckViolation("MetadataProvider must answer with lists, never null");
            }
            Set<String> fieldIds = new HashSet<>();
            for (var field : fieldTypes) {
                String id = field.getIdValue();
                String label = field.getLabelValue();
                if (id == null || id.isBlank() || label == null || label.isBlank()) {
                    throw new TckViolation("Every field type needs an id and a label: " + id + " / " + label);
                }
                if (!fieldIds.add(id)) {
                    throw new TckViolation("Field type id <" + id + "> is declared twice");
                }
                if (field.getOptions() != null && !field.getOptions().isEmpty() && field.getDataType() == null) {
                    throw new TckViolation("Field type <" + id + "> lists options without a dataType");
                }
            }
            Set<String> typeIds = new HashSet<>();
            for (var type : artifactTypes) {
                String id = type.getIdValue();
                if (id == null || id.isBlank() || !typeIds.add(id)) {
                    throw new TckViolation("Every artifact type needs a unique id: " + id);
                }
            }
        } finally {
            BindingLifeCycle.close(instance, context);
        }
    }

    /**
     * A declared rate limit is a promise to the host, which keeps the
     * interval and retries: it must be plausible (at most 10,000 requests per
     * second, at most 10 retries). The adapter must not add retries of its
     * own that multiply the number of attempts; the TCK can check only the
     * plausibility. Skipped without a declared rate limit.
     */
    @Test
    public void aDeclaredRateLimitIsPlausible() {
        RateLimit limit = adapter().descriptor().capabilities().rateLimit();
        assumeTrue(limit != null, "no RateLimit declared — skipped");
        if (limit.maxRequestsPerSecond() > 10_000) {
            throw new TckViolation("A rate limit of " + limit.maxRequestsPerSecond()
                    + " requests per second is no limit — declare none instead");
        }
        if (limit.maxRetries() > 10) {
            throw new TckViolation("More than 10 retries (" + limit.maxRetries()
                    + ") would hide an outage behind minutes of waiting");
        }
    }

    /**
     * The store of the context is persistent: {@code bind()} must not clear
     * or overwrite it, and what an adapter keeps there, the next instance on
     * the same context finds again.
     */
    @Test
    public void bindLeavesTheStoreIntact() throws Exception {
        ContractContext context = contractContext(adapter().descriptor(), configuration());
        context.store().write("tck/marker", "before");
        AdapterInstance instance = BindingLifeCycle.bind(adapter(), context);
        try {
            if (!context.store().read("tck/marker").map("before"::equals).orElse(false)) {
                throw new TckViolation("bind(...) must not clear or overwrite the adapter store");
            }
        } finally {
            BindingLifeCycle.close(instance, context);
        }
    }

    // ------------------------------------------------------------------
    // Bindings: cases 1, 3, 4 (first part) and 5
    // ------------------------------------------------------------------

    /**
     * Case 1: two bindings of the same adapter (one type, as in the host)
     * with different configurations work side by side: separate stores, the
     * same results in parallel threads as alone, an element created in A
     * stays invisible in B, and no value of A (its {@code bindingId}, its
     * configuration) appears in the elements, log or store of B. Each side is
     * also read once as the first and once as the second binding of a type;
     * a failure there means the type keeps state across bindings (a client,
     * cache or tool remembered from the first binding). Details:
     * {@link BindingIsolation}. Skipped without
     * {@link #secondBindingConfiguration()}; without
     * {@link #liveRunContext()} the case reads nothing from the tool.
     */
    @Test
    public void twoBindingsWorkSideBySideWithoutCrossTalk() throws Exception {
        assumeTrue(secondBindingConfiguration().isPresent(),
                "secondBindingConfiguration() is missing — two bindings side by side skipped");
        AdapterDescriptor descriptor = adapter().descriptor();
        Map<String, String> first = configuration();
        Map<String, String> second = secondBindingConfiguration().orElseThrow();
        String project = readingProject(descriptor);
        ToolReading aloneA = null;
        ToolReading aloneB = null;
        if (project != null) {
            //Each side once as the first and once as the second binding of a
            //type: if the type remembers what the first binding reached ("the
            //first client wins"), the second reads the tool of the first.
            //Only the elements are compared, not the page count: the page
            //size may differ
            ToolAdapter firstAThenB = adapter();
            if (firstAThenB == adapter()) {
                System.out.println("[TCK] Case 1: adapter() returns the same object — binding B is never read as the"
                        + " first binding of its type, so a cache per project or address in the type stays unseen;"
                        + " return a fresh adapter from adapter() for the full check");
            }
            aloneA = readAlone(firstAThenB, descriptor, first, project);
            ToolReading bAfterA = readAlone(firstAThenB, descriptor, second, project);
            ToolAdapter firstBThenA = adapter();
            aloneB = readAlone(firstBThenA, descriptor, second, project);
            ToolReading aAfterB = readAlone(firstBThenA, descriptor, first, project);
            requireSecondReadsItsOwnTool("B", aloneB, "A", bAfterA);
            requireSecondReadsItsOwnTool("A", aloneA, "B", aAfterB);
            if (aloneA.elements().equals(aloneB.elements())) {
                throw new TckViolation("The second binding reads the first one's tool: both bindings deliver the same"
                        + " elements although their configurations differ — the ToolAdapter keeps state across"
                        + " bindings (a client, cache or tool remembered from the first binding). Only if"
                        + " secondBindingConfiguration() reaches the very same tool is this the test's fault: it"
                        + " must reach a tool with other content.\n" + aloneA.elements());
            }
        }
        //From here on one type for both bindings, as in the host
        ToolAdapter toolAdapter = adapter();
        ContractContext contextA = contractContext(descriptor, first);
        ContractContext contextB = contractContext(descriptor, second);
        AdapterInstance a = BindingLifeCycle.bind(toolAdapter, contextA);
        try {
            List<String> beforeB = tracesOf(contextA);
            AdapterInstance b = BindingLifeCycle.bind(toolAdapter, contextB);
            try {
                if (!beforeB.equals(tracesOf(contextA))) {
                    throw new TckViolation("Binding B changed what binding A holds (log, store or counters) —"
                            + " bind() works only on its own context.\nBefore: " + beforeB + "\nAfter: "
                            + tracesOf(contextA));
                }
                BindingIsolation.Side sideA = new BindingIsolation.Side("A", a, contextA,
                        BindingIsolation.Scope.direct(),
                        BindingIsolation.markers(contextA, descriptor, first, second),
                        () -> tracesOf(contextA));
                BindingIsolation.Side sideB = new BindingIsolation.Side("B", b, contextB,
                        BindingIsolation.Scope.direct(),
                        BindingIsolation.markers(contextB, descriptor, second, first),
                        () -> tracesOf(contextB));
                BindingIsolation.require(BoundInstance.capabilitiesOf(descriptor, a), sideA, aloneA, sideB, aloneB,
                        project, this::roundTripArtifact, maxPages());
            } finally {
                BindingLifeCycle.close(b, contextB);
            }
        } finally {
            BindingLifeCycle.close(a, contextA);
        }
    }

    /** As the second binding of a type, a side reads the same as it does as the first. */
    private static void requireSecondReadsItsOwnTool(String side, ToolReading asFirst, String firstSide,
                                                     ToolReading asSecond) {
        if (!asFirst.elements().equals(asSecond.elements())) {
            throw new TckViolation("The second binding reads the first one's tool: bound after binding " + firstSide
                    + ", binding " + side + " delivers other elements than as the first binding of the type —"
                    + " the ToolAdapter keeps state across bindings.\nAs first binding:\n"
                    + asFirst.elements() + "\nAfter " + firstSide + ":\n" + asSecond.elements());
        }
    }

    /**
     * Case 3: no process-wide state. The type is loaded once per process and
     * shared by all bindings of all tenants; a mutable static field would
     * carry state from one connection to another. Fails too if
     * {@link #adapterClasses()} is empty. Details: {@link ProcessWideState}.
     */
    @Test
    public void theAdapterClassesKeepNoProcessWideState() {
        Set<Class<?>> classes = adapterClasses();
        //The output says how much was checked: a passing case over three
        //classes says something else than one over a hundred
        System.out.println("[TCK] Case 3: checked " + classes.size() + " classes of " + adapter().getClass().getName());
        if (classes.isEmpty()) {
            throw new TckViolation("adapterClasses() is empty — case 3 would check nothing");
        }
        List<String> violations = ProcessWideState.violations(classes);
        if (!violations.isEmpty()) {
            throw new TckViolation("Process-wide state in the adapter classes (" + violations.size() + " of "
                    + classes.size() + " classes checked) — the type is loaded once per process and shared by every"
                    + " binding of every tenant; keep state in the AdapterInstance, in store() or in the tool:\n  "
                    + String.join("\n  ", violations));
        }
    }

    /** How often case 4 binds after the warm-up round; a leak grows with every binding. */
    static final int RELEASE_ROUNDS = 3;

    /**
     * Case 4, first part: {@code close()} releases, and the same connection
     * (the same context: {@code bindingId}, store, configuration) binds again
     * afterwards, with the same results.
     *
     * <p>The case counts what one binding leaves behind. After a warm-up
     * round (bind, read, close) all living threads are the baseline,
     * including those of a client kept in the type and the global pools of a
     * library that start on demand (such as the event loops of Reactor
     * Netty). The case then binds {@value #RELEASE_ROUNDS} times and fails
     * only if the leftover threads grow with every binding. Before each count
     * it runs {@code System.gc()}, so an unreferenced client ends even on
     * Java 17, which has no {@code HttpClient.close()}. After every
     * {@code close()} the TCK releases what the host releases once the
     * binding is no longer in use ({@link ContractContext#release()}); no
     * stream of {@code http().open(...)} may be open then, a resource without
     * a thread of its own that would otherwise go unnoticed. The other parts
     * of case 4 check the deadline, the caches and the run store.</p>
     */
    @Test
    public void closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        String project = readingProject(descriptor);
        ContractContext context = contractContext(descriptor, configuration());
        ToolReading firstReading = bindReadAndClose(toolAdapter, descriptor, context, project, "first");
        LeftoverThreads threads = new LeftoverThreads(
                thread -> contractHost().ownsThread(thread) || belongsToTheFakeTool(thread));
        Set<Thread> baseline = threads.settledThreads(Set.of(), -1);
        List<Integer> leftover = new ArrayList<>();
        List<Thread> remaining = List.of();
        for (int round = 1; round <= RELEASE_ROUNDS; round++) {
            ToolReading again = bindReadAndClose(toolAdapter, descriptor, context, project, "again");
            if (firstReading != null && !firstReading.equals(again)) {
                throw new TckViolation("After close() and binding the same connection again the adapter"
                        + " delivers other results — a binding must not depend on what an earlier one left"
                        + " behind outside store().\nFirst binding:\n" + firstReading
                        + "\nBound again (" + round + "):\n" + again);
            }
            int previous = leftover.isEmpty() ? 0 : leftover.get(leftover.size() - 1);
            remaining = List.copyOf(threads.newThreads(threads.settledThreads(baseline, previous), true));
            leftover.add(remaining.size());
        }
        boolean growing = leftover.get(0) > 0;
        for (int round = 1; round < leftover.size(); round++) {
            growing &= leftover.get(round) > leftover.get(round - 1);
        }
        if (growing) {
            throw new TckViolation("close() left threads running that the binding started — after "
                    + RELEASE_ROUNDS + " bindings " + leftover + " threads remain, one more set with every"
                    + " binding: " + remaining.stream().map(thread -> thread.getName()
                    + (thread.isDaemon() ? " (daemon)" : "")).toList()
                    + ". close() must stop the binding's pools and clients — an HttpClient from Java 21 with"
                    + " close(); on Java 17 shut down its own executor and keep no reference to it. Threads of"
                    + " executor() belong to the binding as well: release() of the context under test ("
                    + context.getClass().getSimpleName() + ") frees them");
        }
    }

    private ToolReading bindReadAndClose(ToolAdapter toolAdapter, AdapterDescriptor descriptor,
                                         ContractContext context, String project, String what) throws Exception {
        AdapterInstance instance;
        try {
            instance = BindingLifeCycle.bind(toolAdapter, context);
        } catch (Exception e) {
            throw new TckViolation("Binding the same connection " + what + " failed: " + e
                    + " — the host binds anew after every change of configuration, release or generation");
        }
        if (instance == null) {
            throw new TckViolation("bind(...) returned null when binding the same connection " + what);
        }
        try {
            return project == null ? null : ToolReading.of(context, BoundInstance.readerOf(instance),
                    BoundInstance.capabilitiesOf(descriptor, instance), project, maxPages());
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    /**
     * Case 5: configuration comes only from {@code config()}. For every
     * declared key the TCK sets the system properties {@code <key>} (unless
     * the JDK itself reads under that name) and
     * {@code rmm.adapter.<systemkey>.<key>} to trap values, then binds and
     * reads as it did without them: the result must stay the same, and no
     * trap value, including those from the environment
     * ({@link #TRAP_PREFIX}), may appear in elements, log or store. A failure
     * means the adapter reads system properties or environment variables.
     * Without {@link #liveRunContext()} or the direction {@code TO_RMM} the
     * case only binds.
     */
    @Test
    public void configurationComesOnlyFromTheContext() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        String project = readingProject(descriptor);
        Map<String, String> configuration = configuration();
        Observation clean = observe(toolAdapter, descriptor, configuration, project);
        ConfigurationTraps.requireNoTraps(clean.reading(), clean.traces(), ConfigurationTraps.environmentTraps(),
                "with trap values in the environment");

        Map<String, String> configured = new HashMap<>(platformConfiguration());
        configured.putAll(configuration);
        Map<String, String> traps = ConfigurationTraps.systemPropertyTraps(descriptor, configured);
        Set<String> trapValues = new LinkedHashSet<>();
        traps.values().stream().filter(value -> value.contains(TRAP_PREFIX)).forEach(trapValues::add);
        trapValues.addAll(ConfigurationTraps.environmentTraps());
        Map<String, String> previous = ConfigurationTraps.set(traps);
        Observation poisoned;
        try {
            poisoned = observe(toolAdapter, descriptor, configuration, project);
        } catch (Exception | TckViolation e) {
            throw new TckViolation("Binding or reading failed only while the system properties " + traps.keySet()
                    + " carried trap values — the adapter reads system properties instead of config(): " + e);
        } finally {
            ConfigurationTraps.restore(previous);
        }
        if (clean.reading() != null && !clean.reading().equals(poisoned.reading())) {
            throw new TckViolation("The adapter delivers other results while the system properties "
                    + traps.keySet() + " are set — it reads them instead of config()."
                    + "\nWithout:\n" + clean.reading() + "\nWith:\n" + poisoned.reading());
        }
        ConfigurationTraps.requireNoTraps(poisoned.reading(), poisoned.traces(), trapValues,
                "with system property traps " + traps.keySet());
    }

    /**
     * Case 5: a path on the file system of the host belongs to the operator.
     * {@code PATH} is allowed only in scope {@code PLATFORM}; the host
     * registers an adapter with a {@code PATH} key in scope {@code BINDING}
     * as {@code DEFECT}.
     */
    @Test
    public void pathKeysAreDeclaredForThePlatformOnly() {
        for (ConfigKey key : adapter().descriptor().configKeys()) {
            if (key.type() == ConfigType.PATH && key.scope() == ConfigScope.BINDING) {
                throw new TckViolation("Configuration key <" + key.key() + "> is a PATH in scope BINDING —"
                        + " a path on the host's file system belongs to the operator, not to a tenant's"
                        + " connection: declare it with ConfigKey.platform(...). Mirador registers such an"
                        + " adapter as DEFECT");
            }
        }
    }

    // ------------------------------------------------------------------
    // Case 6: the plugin unit
    // ------------------------------------------------------------------

    /** The manifest of a plugin unit, relative to the root of its jar. */
    public static final String PLUGIN_MANIFEST = "META-INF/rmm-adapter.json";

    /**
     * The mapping template of a plugin unit, relative to the root of its jar.
     * The host reads it only here: a path in the manifest does not count, and
     * a file in the configuration directory does not stand in for a plugin.
     */
    public static final String PLUGIN_MAPPING_TEMPLATE = "META-INF/rmm-adapter/mapping-template.json";

    /**
     * Case 6: a plugin unit (a jar that names the adapter for the
     * {@code ServiceLoader}, as the host loads it) carries its manifest
     * {@value #PLUGIN_MANIFEST} and its mapping template
     * {@value #PLUGIN_MAPPING_TEMPLATE}. The manifest matches the descriptor:
     * {@code typeKey}, {@code name}, {@code version}, {@code apiMajor},
     * {@code apiMinor}, a {@code vendor}, the {@code configKeys} with key,
     * type, scope and required flag in the same order, the directions, the
     * hierarchy model and the image reference forms
     * ({@code capabilities.imageReferenceForms}). It names neither
     * {@code mappingTemplate} nor {@code legacyMappingId}: the host reads
     * neither. The template is a mapping that the reader of the host
     * accepts; without a template in the jar, every run of a connection
     * fails in the host with "No element mapping".
     *
     * <p>The TCK checks the code source of the adapter class (in a build, its
     * class directory), never the rest of the class path. An adapter from
     * application code, without a {@code ServiceLoader} entry, is no plugin,
     * and the case is skipped for it. If the unit carries a manifest but does
     * not name the adapter in the {@code ServiceLoader} entry, the case
     * fails: the host does not load such a jar. The host reads the manifest
     * too, without running code of the unit: on upload (image reference forms
     * by its own rule, {@link PluginManifest#HOST_IMAGE_SCHEME}), before
     * loading ({@code apiMinor}), on registration against the descriptor, and
     * in the console before the release. The case is the supplier's own check
     * before that: a declaration of the supplier, not a certification by
     * Mirador.</p>
     */
    @Test
    public void aPluginUnitCarriesAManifestMatchingItsDescriptorAndItsMappingTemplate() throws Exception {
        ToolAdapter toolAdapter = adapter();
        PluginUnit unit = PluginUnit.of(toolAdapter.getClass());
        boolean namedForTheLoader = unit.declaresAsService(toolAdapter.getClass());
        if (!namedForTheLoader && unit.entry(PLUGIN_MANIFEST).isPresent()) {
            throw new TckViolation("Case 6: the plugin unit " + unit + " carries " + PLUGIN_MANIFEST + " but names "
                    + toolAdapter.getClass().getName() + " in no " + PluginUnit.SERVICE_FILE + " — the host does not"
                    + " load the adapter; put its class name into src/main/resources/" + PluginUnit.SERVICE_FILE);
        }
        assumeTrue(namedForTheLoader, "Case 6: " + toolAdapter.getClass().getName()
                + " is no plugin unit (" + unit + " names it in no " + PluginUnit.SERVICE_FILE + ") — skipped");

        PluginManifest manifest = PluginManifest.parse(unit.entry(PLUGIN_MANIFEST).orElseThrow(
                () -> new TckViolation("Case 6: the plugin unit " + unit + " carries no " + PLUGIN_MANIFEST
                        + " — put the manifest of the plugin under src/main/resources/" + PLUGIN_MANIFEST)));
        manifest.requireMatches(toolAdapter.descriptor());

        String template = unit.entry(PLUGIN_MAPPING_TEMPLATE).orElseThrow(() -> new TckViolation("Case 6: the"
                + " plugin unit " + unit + " carries no " + PLUGIN_MAPPING_TEMPLATE + " — the host reads the"
                + " template of a plugin only there (a file in the configuration directory does not stand in),"
                + " and every run of a connection fails with \"No element mapping\" without it"));
        try {
            new IoMappingJsonReader().read(template, unit + "!/" + PLUGIN_MAPPING_TEMPLATE);
        } catch (ExceptionIoMapping unreadable) {
            throw new TckViolation("Case 6: " + PLUGIN_MAPPING_TEMPLATE + " is no mapping the host reads: "
                    + unreadable.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // HTTP path (case 2), life cycle (case 4, other parts), pause, purge() (case 7)
    // ------------------------------------------------------------------

    /**
     * Case 2: network traffic goes only through {@code context.http()}. The
     * fake tool ({@link #networkTool()}) answers only requests with the
     * header that only the HTTP path of the context sets, and every request
     * of a binding must carry its own. With
     * {@link #secondBindingConfiguration()} a second binding works while the
     * first is open, and then the first again: a request with the header of
     * the other binding means the type sends through the HTTP path of another
     * connection; a request with a secret of the other binding (from its
     * {@code config()} or its sign-in) means it sends with a foreign token.
     * Without {@link #liveRunContext()} or a reader, the binding sends through
     * {@code checkHealth()}. Writes, attachments and {@code purge()} are
     * covered by the count after every test
     * ({@link #noRequestOfTheCaseBypassedTheNetworkPath()}). Skipped with
     * {@link #networkOptOut()} or without {@code networkTool()}.
     */
    @Test
    public void everyRequestGoesThroughTheNetworkPathOfItsOwnContext() throws Exception {
        Optional<String> optOut = networkOptOut();
        optOut.ifPresent(reason -> System.out.println("[TCK] Case 2 opted out: " + reason));
        assumeTrue(optOut.isEmpty(), () -> "Case 2 explicitly opted out: " + optOut.orElse(""));
        assumeTrue(networkTool().isPresent(), "networkTool() is missing — HTTP path skipped (adapter without network)");
        FakeHttpTool tool = networkTool().orElseThrow();
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        String project = readingProject(descriptor);
        ContractContext contextA = contractContext(descriptor, configuration());
        AdapterInstance a = BindingLifeCycle.bind(toolAdapter, contextA);
        try {
            OwnNetworkPath.require("A", tool, contextA, null, () -> traffic(a, contextA, descriptor, project));
            Optional<Map<String, String>> second = secondBindingConfiguration();
            if (second.isPresent()) {
                ContractContext contextB = contractContext(descriptor, second.get());
                AdapterInstance b = BindingLifeCycle.bind(toolAdapter, contextB);
                try {
                    OwnNetworkPath.require("B", tool, contextB, contextA, () -> traffic(b, contextB, descriptor,
                            project));
                    OwnNetworkPath.require("A", tool, contextA, contextB, () -> traffic(a, contextA, descriptor,
                            project));
                } finally {
                    BindingLifeCycle.close(b, contextB);
                }
            }
        } finally {
            BindingLifeCycle.close(a, contextA);
        }
    }

    /** What a binding sends to the tool: a reading; without a run context or a reader, the health check. */
    private Void traffic(AdapterInstance instance, ContractContext context, AdapterDescriptor descriptor,
                         String project) throws Exception {
        if (project != null && instance.reader().isPresent()) {
            ToolReading.of(context, instance.reader().orElseThrow(), BoundInstance.capabilitiesOf(descriptor, instance),
                    project, maxPages());
        } else {
            instance.checkHealth();
        }
        return null;
    }

    /**
     * Case 4, deadline: a hook stops soon after the deadline of its run. The
     * TCK reads the artifacts in a run whose deadline has already passed;
     * {@code http()} then sends nothing. A hook may fail or return, but not
     * keep working: one that sends a request that failed at the deadline
     * again and again, or waits for the tool without checking
     * {@link SyncRunContext#expired}, holds up the run. Fails if the read
     * still works after {@link #deadlineGrace()} or if a request reaches
     * {@link #networkTool()}. Skipped without {@link #liveRunContext()} or
     * the direction {@code TO_RMM}.
     */
    @Test
    public void aHookEndsSoonAfterTheDeadlineOfItsRun() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        String project = readingProject(descriptor);
        assumeTrue(project != null, "liveRunContext() is missing — deadline of the run skipped");
        Duration grace = deadlineGrace();
        if (grace == null || grace.isNegative() || grace.compareTo(MAX_DEADLINE_GRACE) > 0) {
            throw new TckViolation("deadlineGrace() is " + grace + " — a hook may work at most "
                    + MAX_DEADLINE_GRACE.toSeconds() + " s after the deadline of its run; a test may shorten the"
                    + " grace, never lengthen it");
        }
        System.out.println("[TCK] Case 4: a hook may work " + grace.toMillis() + " ms after the deadline of its run");
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        int sentBefore = networkTool().map(tool -> tool.requests().size()).orElse(0);
        ExecutorService worker = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "tck-deadline");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            SourceReader reader = BoundInstance.readerOf(instance);
            SyncRunContext run = new SyncRunContext(UUID.randomUUID(), project, Instant.now());
            Future<?> reading = worker.submit(() -> context.inRun(run,
                    () -> ReadContract.readAll(context.paging(), "readArtifacts", reader::readArtifacts, run,
                            ReadScope.full(), maxPages())));
            try {
                reading.get(grace.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException stopped) {
                //Failing at the deadline is one way to stop; a violation of the read contract is not
                if (stopped.getCause() instanceof TckViolation violation) {
                    throw violation;
                }
            } catch (TimeoutException stillWorking) {
                reading.cancel(true);
                throw new TckViolation("readArtifacts(...) still worked " + grace.toMillis() + " ms after"
                        + " the deadline of its run — compare SyncRunContext.deadline() with the clock"
                        + " (expired(now)) and stop; a request that failed at the deadline must not be sent again"
                        + " and again");
            }
            int sent = networkTool().map(tool -> tool.requests().size()).orElse(0) - sentBefore;
            if (sent > 0) {
                throw new TckViolation(sent + " request(s) reached networkTool() in a run whose deadline had passed"
                        + " — without time left http() sends nothing: either the adapter works outside its run (an"
                        + " own thread that does not carry the run; use executor().submit) or the network path of"
                        + " the context under test ignores the deadline of the run (" + context.getClass()
                        .getSimpleName() + ")");
            }
        } finally {
            worker.shutdownNow();
            BindingLifeCycle.close(instance, context);
        }
    }

    /**
     * Case 4, caches: the administrator may clear {@code caches()} at any
     * time; the host then binds the same connection anew. That binding must
     * reload what was lost and deliver the same as before. An adapter that
     * remembers in the type that it has already loaded reads from an empty
     * cache afterwards. Skipped without {@link #liveRunContext()} or the
     * direction {@code TO_RMM}.
     */
    @Test
    public void aNewBindingReloadsWhatTheClearedCachesHeld() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        String project = readingProject(descriptor);
        assumeTrue(project != null, "liveRunContext() is missing — clearing the caches skipped");
        ContractContext context = contractContext(descriptor, configuration());
        ToolReading before = bindReadAndClose(toolAdapter, descriptor, context, project, "first");
        context.clearCaches();
        long left = context.cacheDocuments();
        if (left != 0) {
            throw new TckViolation("After clearCaches() the caches of the connection still hold " + left
                    + " document(s) — the context under test (" + context.getClass().getSimpleName() + ") did not"
                    + " empty caches(), so this case cannot show that the adapter reloads; a defect of the context,"
                    + " not of the adapter");
        }
        ToolReading after = bindReadAndClose(toolAdapter, descriptor, context, project,
                "again after the caches were cleared");
        if (!before.equals(after)) {
            throw new TckViolation("After the caches were cleared, a new binding of the same connection delivers"
                    + " other results — the administrator may clear caches() at any time; the"
                    + " adapter reloads from the tool what they lost, it never assumes they are filled.\nBefore:\n"
                    + before + "\nAfter clearing:\n" + after);
        }
    }

    /**
     * Case 4, run store: {@code runStore()} is empty after the run, and a
     * second run of the same binding delivers the same as the first. An
     * adapter that keeps intermediate state of a run in a field or in
     * {@code store()} carries it into the next run; it belongs in
     * {@code runStore()}, which the host empties at the end of every run.
     * Empty means: the collections of the finished run hold no document, and
     * the connection no longer counts its documents. Skipped without
     * {@link #liveRunContext()} or the direction {@code TO_RMM}.
     */
    @Test
    public void theRunStoreIsEmptyAfterTheRunAndTheNextRunStartsClean() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        String project = readingProject(descriptor);
        assumeTrue(project != null, "liveRunContext() is missing — run store skipped");
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            SourceReader reader = BoundInstance.readerOf(instance);
            SyncRunContext firstRun = new SyncRunContext(UUID.randomUUID(), project);
            ContractContext.Run run = context.startRun(firstRun);
            ToolReading first;
            int held;
            long withTheRun;
            try {
                first = ToolReading.read(context.paging(), reader, BoundInstance.capabilitiesOf(descriptor, instance),
                        firstRun, maxPages());
            } finally {
                try {
                    held = run.documents();
                    withTheRun = context.connectionDocuments();
                } finally {
                    run.close();
                }
            }
            int left = run.documents();
            long afterTheRun = context.connectionDocuments();
            if (left != 0 || afterTheRun != withTheRun - held) {
                throw new TckViolation("The run store of run <" + firstRun.runId() + "> still holds " + left
                        + " of its " + held + " documents after the end of the run (the connection counts "
                        + afterTheRun + " documents, " + (withTheRun - held) + " expected) — the host empties it at"
                        + " the end of every run");
            }
            System.out.println("[TCK] Case 4: the run store held " + held + " documents at the end of the run,"
                    + " none afterwards");
            SyncRunContext secondRun = new SyncRunContext(UUID.randomUUID(), project);
            ToolReading second = context.inRun(secondRun,
                    () -> ToolReading.read(context.paging(), reader,
                            BoundInstance.capabilitiesOf(descriptor, instance), secondRun, maxPages()));
            if (!first.equals(second)) {
                throw new TckViolation("A second run of the same binding delivers other results than the first —"
                        + " the adapter carries state of a run into the next one (a field of the instance or"
                        + " store()); keep what belongs to a run in runStore(), which the host empties at the end"
                        + " of every run.\nFirst run:\n" + first + "\nSecond run:\n" + second);
            }
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    /**
     * A long pause between two pages: a run can stand still for a long time
     * between two calls of {@code readArtifacts} (rate limit, scheduling, a
     * paused tenant). State on the tool's side, such as a search session or a
     * result set, has expired by then ({@link #expireToolState()}). The
     * cursor of the previous page must still lead on, to the same elements:
     * it carries what the next page needs (an offset, the last key, the
     * query), not only an id of the tool that expires. Skipped without
     * {@link #liveRunContext()} or the direction {@code TO_RMM}, if the read
     * has only one page (a smaller page size in {@link #configuration()}
     * checks the cursor), or if there is neither a {@link #networkTool()}
     * nor an overridden {@code expireToolState()}.
     */
    @Test
    public void aCursorSurvivesALongPauseBetweenTwoPages() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        String project = readingProject(descriptor);
        assumeTrue(project != null, "liveRunContext() is missing — pause between two pages skipped");
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            SourceReader reader = BoundInstance.readerOf(instance);
            SyncRunContext straight = new SyncRunContext(UUID.randomUUID(), project);
            ReadContract.PhaseResult<ElementArtifact> uninterrupted = context.inRun(straight,
                    () -> ReadContract.readAll(context.paging(), "readArtifacts", reader::readArtifacts, straight,
                            ReadScope.full(), maxPages()));
            SyncRunContext pausing = new SyncRunContext(UUID.randomUUID(), project);
            List<String> withPause = context.inRun(pausing, () -> PausedRead.read(reader, pausing, this::maxPages,
                    this::expireToolStateBetweenPages, this::pauseBetweenPages));
            List<String> expected = PausedRead.signaturesOf(uninterrupted.elements(), uninterrupted.failures());
            if (!expected.equals(withPause)) {
                throw new TckViolation("After a long pause between two pages readArtifacts(...) delivers other"
                        + " elements — the state on the tool's side expired meanwhile; a cursor carries what the"
                        + " next page needs (an offset, the last key, the query), not only a server-side id.\n"
                        + "Without a pause:\n" + expected + "\nWith a pause:\n" + withPause);
            }
        } finally {
            BindingLifeCycle.closeAndRelease(instance, context);
        }
    }

    /**
     * Expires the state of the tool ({@link #expireToolState()}) and makes
     * sure that something expires: with the default, {@link #networkTool()}
     * must have registered state, otherwise the test fails; without
     * {@code networkTool()} and without an overridden
     * {@code expireToolState()} there is nothing to expire, and the test is
     * skipped.
     */
    private void expireToolStateBetweenPages() {
        if (overrides("expireToolState")) {
            expireToolState();
            return;
        }
        Optional<FakeHttpTool> tool = networkTool();
        assumeTrue(tool.isPresent(), "Pause between two pages skipped: no state of the tool that could expire —"
                + " neither networkTool() nor an overridden expireToolState()");
        if (tool.get().expiryHandlers() == 0) {
            throw new TckViolation("The pause case lets the state on the tool's side expire, but networkTool()"
                    + " registers none (FakeHttpTool.onExpiry) — register the tool's search sessions or result sets"
                    + " there, or override expireToolState() for a tool that keeps no state; otherwise the pause"
                    + " would check nothing");
        }
        expireToolState();
    }

    /** Whether the test class itself overrides {@code method()} (without parameters). */
    private boolean overrides(String method) {
        for (Class<?> type = getClass(); type != null && type != AdapterContractTest.class;
             type = type.getSuperclass()) {
            try {
                type.getDeclaredMethod(method);
                return true;
            } catch (NoSuchMethodException notHere) {
                //look further up the hierarchy
            }
        }
        return false;
    }

    /**
     * Case 7: {@code purge()} removes only the adapter's own helper objects
     * in the tool (webhook registrations, sessions) when the connection is
     * removed for good, never content. A binding reads the same before and
     * after, for {@link #configuration()} and, if there is one, for
     * {@link #secondBindingConfiguration()} (another project, another tool).
     * With {@link #networkTool()} the output names every request of
     * {@code purge()} that does more than read. Skipped without
     * {@link #liveRunContext()} or the direction {@code TO_RMM}.
     */
    @Test
    public void purgeChangesNoContentOfTheTool() throws Exception {
        ToolAdapter toolAdapter = adapter();
        AdapterDescriptor descriptor = toolAdapter.descriptor();
        String project = readingProject(descriptor);
        assumeTrue(project != null, "liveRunContext() is missing — purge() skipped");
        List<Map<String, String>> readings = new ArrayList<>(List.of(configuration()));
        secondBindingConfiguration().ifPresent(readings::add);
        List<ToolReading> before = new ArrayList<>();
        for (Map<String, String> reading : readings) {
            before.add(readAlone(toolAdapter, descriptor, reading, project));
        }
        int sentBefore = networkTool().map(tool -> tool.requests().size()).orElse(0);
        ContractContext context = contractContext(descriptor, configuration());
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            instance.purge();
        } finally {
            BindingLifeCycle.close(instance, context);
        }
        networkTool().ifPresent(tool -> {
            List<FakeHttpTool.Request> sent = tool.requests();
            List<FakeHttpTool.Request> changing = sent.subList(Math.min(sentBefore, sent.size()), sent.size()).stream()
                    .filter(request -> !request.method().equals("GET") && !request.method().equals("HEAD")).toList();
            System.out.println("[TCK] Case 7: purge() sent " + (changing.isEmpty() ? "no request that changes"
                    + " anything" : changing));
        });
        for (int index = 0; index < readings.size(); index++) {
            ToolReading after = readAlone(toolAdapter, descriptor, readings.get(index), project);
            if (!before.get(index).elements().equals(after.elements())) {
                throw new TckViolation("purge() changed the content of the tool"
                        + (index == 0 ? "" : " (read through secondBindingConfiguration())") + " — it removes only"
                        + " the adapter's own helper objects (webhook registrations, sessions), never elements"
                        + " (case 7).\nBefore:\n" + before.get(index) + "\nAfter purge():\n" + after);
            }
        }
    }

    /** The project of the readings in cases 1, 2, 4, 5 and 7; null without a run context or the read direction. */
    private String readingProject(AdapterDescriptor descriptor) {
        if (liveRunContext().isEmpty() || !descriptor.capabilities().directions().contains(SyncDirection.TO_RMM)) {
            return null;
        }
        return liveRunContext().orElseThrow().externalProjectId();
    }

    private ToolReading readAlone(ToolAdapter toolAdapter, AdapterDescriptor descriptor,
                                  Map<String, String> configuration, String project) throws Exception {
        ContractContext context = contractContext(descriptor, configuration);
        AdapterInstance alone = BindingLifeCycle.bind(toolAdapter, context);
        try {
            return ToolReading.of(context, BoundInstance.readerOf(alone),
                    BoundInstance.capabilitiesOf(descriptor, alone), project, maxPages());
        } finally {
            BindingLifeCycle.close(alone, context);
        }
    }

    /** What a binding left behind: log lines, store entries and metric counters. */
    private static List<String> tracesOf(ContractContext context) {
        List<String> traces = new ArrayList<>(context.logLines());
        new TreeMap<>(context.storeEntries()).forEach((key, value) -> traces.add(key + "=" + value));
        traces.add("metrics: requests=" + context.toolRequests() + " retries=" + context.toolRetries()
                + " throttle-ms=" + context.throttleWaitMillis() + " http=" + context.httpRequests());
        return traces;
    }

    /** One bind with its reading (given a run context) and what the binding left behind. */
    private record Observation(ToolReading reading, List<String> traces) {
    }

    private Observation observe(ToolAdapter toolAdapter, AdapterDescriptor descriptor,
                                Map<String, String> configuration, String project) throws Exception {
        ContractContext context = contractContext(descriptor, configuration);
        AdapterInstance instance = BindingLifeCycle.bind(toolAdapter, context);
        try {
            ToolReading reading = project == null ? null : ToolReading.of(context, BoundInstance.readerOf(instance),
                    BoundInstance.capabilitiesOf(descriptor, instance), project, maxPages());
            return new Observation(reading, tracesOf(context));
        } finally {
            BindingLifeCycle.close(instance, context);
        }
    }

    /**
     * Whether a thread belongs to the fake tool of the test (a test server
     * that starts threads on demand) and is therefore not counted against the
     * binding in case 4. Default: none; the TCK exempts nothing by name. The
     * output lists what is exempted.
     */
    protected boolean belongsToTheFakeTool(Thread thread) {
        return false;
    }

    // ------------------------------------------------------------------
    // Attachments: metadata, byte channel, round trip
    // ------------------------------------------------------------------

    /**
     * The attachment phase: every attachment read carries the metadata the
     * sync core works with: its own id, unique in the read (stored as the
     * reference), its parent element (which artifact the file belongs to) and
     * a file name. The completeness statement is free of contradiction: a
     * parent element with a read failure must not count as complete
     * ({@code completeParentIds}), otherwise the reconciliation would delete
     * what the adapter merely failed to read. And the byte channel keeps its
     * announcement: {@code openAttachment} delivers exactly the announced
     * number of bytes, which the host announces to the object store before
     * the first byte flows (checked on a sample of up to 3 attachments of at
     * most 5 MiB each). Skipped without {@link #liveRunContext()} or unless
     * the adapter declares that it reads the attachment phase.
     */
    @Test
    public void readAttachmentsAnnouncesUsableMetadataAndExactStreams() throws Exception {
        assumeTrue(liveRunContext().isPresent(),
                "liveRunContext() is missing — attachment read check skipped");
        assumeTrue(adapter().descriptor().capabilities().reads(SyncPhase.ATTACHMENTS),
                "The adapter does not declare the attachment phase as a source");
        SyncRunContext runContext = liveRunContext().orElseThrow();
        Live live = live(adapter(), configuration(), runContext);
        AdapterInstance instance = live.instance();
        try {
            SourceReader reader = BoundInstance.readerOf(instance);
            //The order of the sync core: the artifacts first. An adapter that collects
            //the attachments while it reads the artifacts has its data set afterwards
            ReadContract.readAll(live.context().paging(), "readArtifacts", reader::readArtifacts, runContext,
                    ReadScope.full(), maxPages());
            AttachmentRead read = AttachmentRead.once(reader, runContext, this::maxPages);
            read.requireUsableMetadata();
            read.requireExactStreams(reader, runContext);
        } finally {
            live.close();
        }
    }

    /**
     * The round trip of an attachment: create it, find it again at its
     * artifact, read back its bytes, delete it. Deleting is idempotent,
     * because the host repeats it after a timeout. The attachment phase is
     * read only once: the contract promises no more per run. Skipped without
     * {@link #roundTripAttachment()}, {@link #liveRunContext()} or
     * {@link #roundTripArtifact()}, or unless adapter and binding declare the
     * attachment phase in both directions.
     */
    @Test
    public void attachmentRoundTripCreatesFindsAndDeletesTheFile() throws Exception {
        assumeTrue(roundTripAttachment().isPresent(),
                "roundTripAttachment() is missing — attachment round trip skipped");
        AdapterCapabilities capabilities = adapter().descriptor().capabilities();
        assumeTrue(capabilities.writes(SyncPhase.ATTACHMENTS) && capabilities.reads(SyncPhase.ATTACHMENTS),
                "The adapter does not declare the attachment phase in both directions");
        requireLiveRoundTrip();
        SyncRunContext runContext = liveRunContext().orElseThrow();
        Live live = liveInstance(runContext);
        AdapterInstance instance = live.instance();
        try {
            AdapterCapabilities ofTheBinding = BoundInstance.capabilitiesOf(adapter().descriptor(), instance);
            assumeTrue(ofTheBinding.writes(SyncPhase.ATTACHMENTS) && ofTheBinding.reads(SyncPhase.ATTACHMENTS),
                    "The binding does not declare the attachment phase in both directions");
            TargetWriter writer = BoundInstance.writerOf(instance);
            SourceReader reader = BoundInstance.readerOf(instance);
            String parentId = writer.createArtifact(runContext, roundTripArtifact().orElseThrow());
            try {
                ElementAttachment attachment = roundTripAttachment().orElseThrow();
                attachment.setParentId(new ElementId(parentId));
                attachment.setByteCount(ROUND_TRIP_BYTES.length);
                String contentType = attachment.getContentType();
                String attachmentId = writer.createAttachment(runContext, attachment,
                        () -> new AttachmentContent(new ByteArrayInputStream(ROUND_TRIP_BYTES),
                                ROUND_TRIP_BYTES.length, contentType));
                if (attachmentId == null || attachmentId.isBlank()) {
                    throw new TckViolation("createAttachment must return the id the tool assigned — Mirador"
                            + " stores it as the attachment's external reference; without it the next run"
                            + " would upload the same file again");
                }
                //The order of the sync core, as above: the artifacts first
                ReadContract.readAll(live.context().paging(), "readArtifacts", reader::readArtifacts, runContext,
                        ReadScope.full(), maxPages());
                ElementAttachment found = AttachmentRead.once(reader, runContext, this::maxPages).attachments().stream()
                        .filter(read -> attachmentId.equals(read.getIdValue()))
                        .findFirst()
                        .orElseThrow(() -> new TckViolation("The attachment created as <" + attachmentId
                                + "> was not delivered by readAttachments — what the adapter writes must be"
                                + " readable, otherwise the next run would upload it again"));
                if (!parentId.equals(found.getParentIdValue())) {
                    throw new TckViolation("The attachment <" + attachmentId + "> was created at <" + parentId
                            + "> but is read at <" + found.getParentIdValue() + ">");
                }
                try (AttachmentContent content = reader.openAttachment(runContext, attachmentId)) {
                    if (content == null) {
                        throw new TckViolation("openAttachment(<" + attachmentId + ">) returned null"
                                + " — report a missing file as an AdapterException, never as no content");
                    }
                    //One byte more than written: this also detects a stream that is
                    //too long without pulling all of it into memory
                    byte[] bytes = content.stream().readNBytes(ROUND_TRIP_BYTES.length + 1);
                    if (!Arrays.equals(ROUND_TRIP_BYTES, bytes)) {
                        throw new TckViolation("openAttachment delivered other bytes than createAttachment was"
                                + " given (" + bytes.length + " instead of " + ROUND_TRIP_BYTES.length + ")");
                    }
                    if (content.byteCount() != bytes.length) {
                        throw new TckViolation("openAttachment announced " + content.byteCount()
                                + " bytes but delivered " + bytes.length);
                    }
                }
                ElementAttachment toDelete = new ElementAttachment(new ElementId(attachmentId),
                        new ElementId(parentId), found.getFileName());
                writer.deleteAttachment(runContext, toDelete);
                try {
                    writer.deleteAttachment(runContext, toDelete);
                } catch (Exception e) {
                    throw new TckViolation("Deleting an attachment that is already gone must not fail — the"
                            + " host repeats deletes after a timeout, and an attachment that cannot be"
                            + " deleted twice stays unacknowledged forever: " + e.getMessage());
                }
            } finally {
                //Clean up the artifact: it takes along any attachment
                //left over from a failed run
                writer.deleteArtifact(runContext, parentId);
            }
        } finally {
            live.close();
        }
    }
}
