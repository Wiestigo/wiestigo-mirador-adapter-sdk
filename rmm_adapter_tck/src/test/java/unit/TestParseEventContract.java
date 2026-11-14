package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the TCK case for {@code parseEvent}, the interpretation of a webhook. An empty result, a read scope
 * with named identifiers and an {@code AdapterException} pass. Any other exception, {@code null}, a read
 * scope that covers everything, waiting past the deadline and a request to the tool are violations.
 */
public class TestParseEventContract {

    /** What {@code parseEvent} does; the binding's context is passed along. */
    @FunctionalInterface
    interface Parse {
        Optional<ReadScope> parse(AdapterContext context, Map<String, List<String>> headers, InputStream body)
                throws AdapterException;
    }

    private static ToolAdapter adapterWith(Parse parse) {
        return new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return new AdapterDescriptor("EVTEST", "Event Test", "0.1", AdapterApiVersion.MAJOR,
                        new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT, false, false,
                                false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null),
                        List.of(), List.of());
            }

            @Override
            public AdapterInstance bind(AdapterContext context) {
                return new AdapterInstance() {
                    @Override
                    public Optional<SourceReader> reader() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<TargetWriter> writer() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<ReadScope> parseEvent(Map<String, List<String>> headers, InputStream body)
                            throws AdapterException {
                        return parse.parse(context, headers, body);
                    }
                };
            }
        };
    }

    /**
     * The TCK with its default deadline of 5 s: on a loaded machine a new thread and the first class loading
     * may take longer than a few hundred milliseconds. Only the test that must exceed the deadline shortens
     * it ({@link #tckWithAShortDeadlineFor}).
     */
    private static AdapterContractTest tckFor(Parse parse) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapterWith(parse);
            }
        };
    }

    /** The TCK with a deadline of 300 ms, for the test that waits. */
    private static AdapterContractTest tckWithAShortDeadlineFor(Parse parse) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapterWith(parse);
            }

            @Override
            protected Duration parseEventDeadline() {
                return Duration.ofMillis(300);
            }
        };
    }

    @Test
    public void emptyNamedIdsAndAnAdapterExceptionPass() {
        assertDoesNotThrow(() -> tckFor((context, headers, body) -> Optional.empty())
                .parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());
        assertDoesNotThrow(() -> tckFor((context, headers, body) -> {
            try {
                body.readAllBytes();
            } catch (java.io.IOException unreadable) {
                throw new AdapterException("the event cannot be read", unreadable);
            }
            return Optional.of(ReadScope.ofIds(List.of("E-1", "E-2")));
        }).parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());
        assertDoesNotThrow(() -> tckFor((context, headers, body) -> {
            throw new AdapterException("not an event of this tool");
        }).parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());
        assertDoesNotThrow(() -> new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return new DefaultParseEventAdapter().adapter();
            }
        }.parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest(), "the default of the API passes");
    }

    @Test
    public void aRuntimeExceptionIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor((context, headers, body) -> {
            throw new NullPointerException("headers.get(\"x-tool\")");
        }).parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());

        assertTrue(violation.getMessage().contains("NullPointerException")
                && violation.getMessage().contains("AdapterException"), violation.getMessage());
    }

    @Test
    public void nullAndAReadOfEverythingAreViolations() {
        assertThrows(TckViolation.class, () -> tckFor((context, headers, body) -> null)
                .parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());
        for (ReadScope tooWide : List.of(ReadScope.full(), ReadScope.since(java.time.Instant.EPOCH),
                ReadScope.ofIds(IntStream.rangeClosed(1, AdapterContractTest.MAX_EVENT_IDS + 1)
                        .mapToObj(i -> "E-" + i).collect(Collectors.toList())))) {
            TckViolation violation = assertThrows(TckViolation.class, () -> tckFor((context, headers, body) ->
                    Optional.of(tooWide)).parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());
            assertTrue(violation.getMessage().contains("names its elements"), violation.getMessage());
        }
    }

    /**
     * An identifier that the host silently drops (empty after trimming, or longer than
     * {@link AdapterContractTest#MAX_EVENT_ID_LENGTH}) is a violation: if none remains, the run reads the
     * whole project. Exactly {@code MAX_EVENT_ID_LENGTH} characters after trimming pass.
     */
    @Test
    public void anIdTheHostDropsIsAViolation() {
        String longest = "E".repeat(AdapterContractTest.MAX_EVENT_ID_LENGTH);
        for (List<String> dropped : List.of(List.of(longest + "1"), List.of("E-1", "\u0000 \u001f"))) {
            TckViolation violation = assertThrows(TckViolation.class, () -> tckFor((context, headers, body) ->
                    Optional.of(ReadScope.ofIds(dropped))).parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest(),
                    dropped.toString());
            assertTrue(violation.getMessage().contains("names its elements"), violation.getMessage());
        }
        assertDoesNotThrow(() -> tckFor((context, headers, body) -> Optional.of(ReadScope.ofIds(List.of(
                " " + longest + " ")))).parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());
    }

    @Test
    public void waitingPastTheDeadlineIsAViolation() {
        CountDownLatch never = new CountDownLatch(1);
        TckViolation violation = assertThrows(TckViolation.class, () -> tckWithAShortDeadlineFor((context, h, b) -> {
            try {
                never.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }).parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());

        assertTrue(violation.getMessage().contains("took longer than 300 ms"), violation.getMessage());
    }

    @Test
    public void aRequestToTheToolIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor((context, headers, body) -> {
            try {
                context.http().send(AdapterRequest.builder("GET", URI.create("http://127.0.0.1:9/event")).build());
            } catch (AdapterException unreachable) {
                //the request counts as sent even though it failed
            }
            return Optional.empty();
        }).parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());

        assertTrue(violation.getMessage().contains("request(s) to the tool"), violation.getMessage());
    }

    @Test
    public void aLongerDeadlineThanTheMaximumIsRefused() {
        TckViolation violation = assertThrows(TckViolation.class, () -> new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapterWith((context, headers, body) -> Optional.empty());
            }

            @Override
            protected Duration parseEventDeadline() {
                return Duration.ofSeconds(6);
            }
        }.parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest());

        assertTrue(violation.getMessage().contains("never lengthen"), violation.getMessage());
    }

    /** An adapter that does not override {@code parseEvent} and so keeps the API's default. */
    static final class DefaultParseEventAdapter {
        ToolAdapter adapter() {
            return new ToolAdapter() {
                @Override
                public AdapterDescriptor descriptor() {
                    return adapterWith((context, headers, body) -> Optional.empty()).descriptor();
                }

                @Override
                public AdapterInstance bind(AdapterContext context) {
                    return new AdapterInstance() {
                        @Override
                        public Optional<SourceReader> reader() {
                            return Optional.empty();
                        }

                        @Override
                        public Optional<TargetWriter> writer() {
                            return Optional.empty();
                        }
                    };
                }
            };
        }
    }
}
