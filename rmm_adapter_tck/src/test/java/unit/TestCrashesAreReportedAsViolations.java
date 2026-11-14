package unit;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that an adapter which answers {@code null} in the place of an outcome, or binds without the port of
 * a declared direction, fails its case with a {@link TckViolation} that names the fault, and that a case
 * which fails still closes and releases its binding. Runs against the in-memory reference tool, of which one
 * call answers differently here.
 */
public class TestCrashesAreReportedAsViolations {

    /** What a call answers instead: gets the arguments and what the reference tool answered. */
    @FunctionalInterface
    private interface Answer {
        Object to(Object[] arguments, Object answered) throws Throwable;
    }

    private static ElementArtifact template() {
        ElementArtifact artifact = ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                new ElementLabel("Requirement"));
        artifact.addField(new ElementFieldString(new ElementName("name"), new ElementLabel("Name"), "Brake"));
        artifact.addField(new ElementFieldString(new ElementName("description"), new ElementLabel("Description"),
                "The brake brakes."));
        return artifact;
    }

    /** {@code target} behind its interface, with the answer of {@code method} replaced by {@code answer}. */
    private static <T> T answering(Class<T> type, T target, String method, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, call, arguments) -> {
                    Object answered;
                    try {
                        answered = call.invoke(target, arguments);
                    } catch (InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                    return call.getName().equals(method) ? answer.to(arguments, answered) : answered;
                }));
    }

    /** The reference tool whose bound instance answers {@code method} with {@code answer}. */
    private static ToolAdapter bindingWith(String method, Answer answer) {
        return answering(ToolAdapter.class, TestInMemoryAdapterContract.seeded(), "bind",
                (arguments, bound) -> answering(AdapterInstance.class, (AdapterInstance) bound, method, answer));
    }

    /** The reference tool whose writer answers {@code hook} with {@code answer}. */
    private static ToolAdapter writingWith(String hook, Answer answer) {
        return bindingWith("writer", (arguments, writer) -> Optional.of(answering(TargetWriter.class,
                ((Optional<?>) writer).map(TargetWriter.class::cast).orElseThrow(), hook, answer)));
    }

    /** The outcomes of a batch with {@code null} in the place of the outcome at {@code position}. */
    private static List<TargetOutcome> withNullAt(Object outcomes, int position) {
        List<TargetOutcome> changed = new ArrayList<>();
        ((List<?>) outcomes).forEach(outcome -> changed.add((TargetOutcome) outcome));
        changed.set(position, null);
        return changed;
    }

    private static AdapterContractTest tckFor(ToolAdapter adapter) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapter;
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "MEM-PRJ"));
            }

            @Override
            protected Optional<ElementArtifact> roundTripArtifact() {
                return Optional.of(template());
            }

            @Override
            protected Optional<String> roundTripRelationshipType() {
                return Optional.of("relates");
            }
        };
    }

    @Test
    void aBatchThatAnswersNullForAnElementIsAViolation() {
        AdapterContractTest tck = tckFor(writingWith("createArtifacts",
                (arguments, outcomes) -> withNullAt(outcomes, 1)));

        TckViolation violation = assertThrows(TckViolation.class,
                tck::declaredArtifactBulkHooksAnswerOneOutcomePerElementInOrder);

        assertTrue(violation.getMessage().contains("createArtifacts answered null instead of a TargetOutcome for"
                + " element 1"), violation.getMessage());
    }

    @Test
    void aSelectionThatIsAnsweredWithNullIsAViolation() {
        AdapterContractTest tck = tckFor(writingWith("writeElementFeatures",
                (arguments, outcomes) -> Collections.singletonList(null)));

        TckViolation violation = assertThrows(TckViolation.class,
                tck::variabilityRoundTripMirrorsTheSelectionOfAnElement);

        assertTrue(violation.getMessage().contains("writeElementFeatures answered null instead of a TargetOutcome"),
                violation.getMessage());
    }

    @Test
    void aBindingWithoutTheReaderOfADeclaredDirectionIsAViolation() {
        AdapterContractTest tck = tckFor(bindingWith("reader", (arguments, reader) -> Optional.empty()));

        TckViolation violation = assertThrows(TckViolation.class, tck::everyReadPhaseTerminatesWithSaneCursors);

        assertTrue(violation.getMessage().contains("declares the direction TO_RMM")
                && violation.getMessage().contains("provides no SourceReader"), violation.getMessage());
    }

    @Test
    void aBindingWithoutTheWriterOfADeclaredDirectionIsAViolation() {
        AdapterContractTest tck = tckFor(bindingWith("writer", (arguments, writer) -> Optional.empty()));

        TckViolation violation = assertThrows(TckViolation.class, tck::updateAndDeleteAreIdempotent);

        assertTrue(violation.getMessage().contains("declares the direction FROM_RMM")
                && violation.getMessage().contains("provides no TargetWriter"), violation.getMessage());
    }

    @Test
    void aDeltaReadWithoutAReaderNamesTheDeclaredCapability() {
        AdapterContractTest tck = tckFor(bindingWith("reader", (arguments, reader) -> Optional.empty()));

        TckViolation violation = assertThrows(TckViolation.class, tck::aDeclaredDeltaReadAcceptsADeltaScope);

        assertTrue(violation.getMessage().contains("declares supportsDeltaRead")
                && violation.getMessage().contains("provides no SourceReader"), violation.getMessage());
    }

    @Test
    void bindThatReturnsNullFailsTheConsistencyCaseAsAViolation() {
        AdapterContractTest tck = tckFor(answering(ToolAdapter.class, TestInMemoryAdapterContract.seeded(), "bind",
                (arguments, bound) -> null));

        TckViolation violation = assertThrows(TckViolation.class,
                tck::bindYieldsAnInstanceConsistentWithTheCapabilities);

        assertEquals("bind(...) returned null", violation.getMessage());
    }

    @Test
    void theContextIsReleasedEvenIfCloseFails() {
        AtomicReference<AdapterHttp> http = new AtomicReference<>();
        ToolAdapter tool = answering(ToolAdapter.class, TestInMemoryAdapterContract.seeded(), "bind",
                (arguments, bound) -> {
                    http.set(((AdapterContext) arguments[0]).http());
                    return answering(AdapterInstance.class, (AdapterInstance) bound, "close", (none, closed) -> {
                        throw new UnsupportedOperationException("close failed");
                    });
                });

        assertThrows(UnsupportedOperationException.class, tckFor(tool)::bindLeavesTheStoreIntact);

        //A released context has closed its network path: a send on it is refused, not attempted
        assertThrows(IllegalStateException.class,
                () -> http.get().send(AdapterRequest.get(URI.create("http://127.0.0.1:9/tck-test"))));
    }

    @Test
    void theBatchCaseClosesItsBindingIfTheBindingDeclaresNoCapabilities() {
        AtomicInteger closed = new AtomicInteger();
        AdapterContractTest tck = tckFor(closingCounted(closed));

        TckViolation violation = assertThrows(TckViolation.class,
                tck::declaredArtifactBulkHooksAnswerOneOutcomePerElementInOrder);

        assertTrue(violation.getMessage().contains("capabilities() of the bound instance returned null"),
                violation.getMessage());
        assertEquals(1, closed.get(), "the binding of the case is closed");
    }

    @Test
    void theRelationshipCaseClosesItsBindingIfTheBindingDeclaresNoCapabilities() {
        AtomicInteger closed = new AtomicInteger();
        AdapterContractTest tck = tckFor(closingCounted(closed));

        TckViolation violation = assertThrows(TckViolation.class,
                tck::relationshipChangesFollowTheDeclaredUpdatePath);

        assertTrue(violation.getMessage().contains("capabilities() of the bound instance returned null"),
                violation.getMessage());
        assertEquals(1, closed.get(), "the binding of the case is closed");
    }

    /** The reference tool whose binding answers {@code capabilities()} with {@code null} and counts its {@code close()}. */
    private static ToolAdapter closingCounted(AtomicInteger closed) {
        return answering(ToolAdapter.class, TestInMemoryAdapterContract.seeded(), "bind",
                (arguments, bound) -> answering(AdapterInstance.class, answering(AdapterInstance.class,
                        (AdapterInstance) bound, "capabilities", (none, own) -> null), "close", (none, done) -> {
                    closed.incrementAndGet();
                    return done;
                }));
    }
}
