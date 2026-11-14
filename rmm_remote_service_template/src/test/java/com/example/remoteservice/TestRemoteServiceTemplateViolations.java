package com.example.remoteservice;

import com.rmm.remote.tck.RemoteServiceContractTest;
import com.rmm.remote.tck.ServiceContractViolation;
import com.sun.net.httpserver.HttpsExchange;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The negative tests of the template: each variant of the template that
 * violates the protocol fails EXACTLY its case, the others pass and none is
 * skipped. The variants: without client authentication (case 1), believing
 * the header {@code X-RMM-Binding} (case 2), one tool for all bindings
 * (case 3). Each variant overrides exactly the one place of the template that
 * carries the obligation. As under JUnit, each case runs on a fresh instance
 * with its own service.
 */
public class TestRemoteServiceTemplateViolations {

    /** A case of the TCK; throws what the case throws. */
    private interface ContractCase {
        void run(RemoteServiceContractTest contract) throws Exception;
    }

    /** The result of the cases against a variant: the message of each failed and of each skipped case. */
    private record Verdict(Map<String, String> violations, Map<String, String> skipped) {
    }

    /** A variant: its pin store and its service. */
    private record Variant(Function<Path, PinStore> pins, Function<ServiceSettings, RemoteService> service) {
    }

    private static final Map<String, ContractCase> CASES = new LinkedHashMap<>();

    static {
        CASES.put("case 1", RemoteServiceContractTest::aRequestWithoutAPinnedClientCertificateIsRefused);
        CASES.put("case 2", RemoteServiceContractTest::theBindingHeaderMustNameTheBindingOfTheCertificate);
        CASES.put("case 3", RemoteServiceContractTest::twoBindingsKeepTheirDataApart);
        CASES.put("case 4", RemoteServiceContractTest::theServiceSpeaksProtocolVersion4);
        CASES.put("case 5", RemoteServiceContractTest::writeAnswersFollowTheOutcomeRules);
    }

    /** The template as it is. */
    private static final Variant AS_SPECIFIED = new Variant(PinStore::ofDirectory, RemoteService::new);

    private static Verdict verdict(Variant variant) throws Exception {
        Map<String, String> violations = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        for (Map.Entry<String, ContractCase> contractCase : CASES.entrySet()) {
            TestRemoteServiceTemplateContract contract = new TestRemoteServiceTemplateContract() {
                @Override
                protected PinStore pinStore(Path directory) {
                    return variant.pins().apply(directory);
                }

                @Override
                protected RemoteService newService(ServiceSettings settings) {
                    return variant.service().apply(settings);
                }
            };
            contract.startService();
            try {
                contractCase.getValue().run(contract);
            } catch (ServiceContractViolation violation) {
                violations.put(contractCase.getKey(), violation.getMessage());
            } catch (TestAbortedException skip) {
                //A skipped case is not a passed one
                skipped.put(contractCase.getKey(), skip.getMessage());
            } finally {
                contract.stopService();
            }
        }
        return new Verdict(violations, skipped);
    }

    private static void passesEveryCase(Variant variant) throws Exception {
        Verdict verdict = verdict(variant);
        assertEquals(Map.of(), verdict.violations());
        assertEquals(Map.of(), verdict.skipped(), "no case may be skipped");
    }

    /** Fails exactly {@code expected}, with a message that contains {@code fragment}; no case is skipped. */
    private static void failsExactly(Variant variant, String expected, String fragment) throws Exception {
        Verdict verdict = verdict(variant);
        assertEquals(List.of(expected), List.copyOf(verdict.violations().keySet()), verdict.violations().toString());
        assertTrue(verdict.violations().get(expected).contains(fragment), verdict.violations().get(expected));
        assertEquals(Set.of(), verdict.skipped().keySet(), verdict.skipped().toString());
    }

    @Test
    public void theTemplatePassesEveryCase() throws Exception {
        passesEveryCase(AS_SPECIFIED);
    }

    /**
     * Without client authentication: the handshake only requests a
     * certificate and every presented one is accepted (the pin store accepts
     * any, also in the check per request); the header is still checked
     * against the name in the certificate. A certificate that is not pinned
     * gets through, including one issued with the name of binding A.
     */
    @Test
    public void withoutClientAuthenticationTheTemplateFailsOnlyCase1() throws Exception {
        failsExactly(new Variant(directory -> new PinStore(null, List.of()) {
            @Override
            public boolean isPinned(X509Certificate leaf) {
                return leaf != null;
            }
        }, settings -> new RemoteService(settings) {
            @Override
            protected void configureTls(SSLParameters parameters, Set<String> supportedCipherSuites) {
                super.configureTls(parameters, supportedCipherSuites);
                parameters.setWantClientAuth(true);
            }
        }), "case 1", "with a client certificate it has not pinned with 200");
    }

    /** Believes the header {@code X-RMM-Binding} instead of checking it against the certificate. */
    @Test
    public void believingTheBindingHeaderTheTemplateFailsOnlyCase2() throws Exception {
        failsExactly(new Variant(PinStore::ofDirectory, settings -> new RemoteService(settings) {
            @Override
            protected String bindingOf(HttpsExchange exchange, X509Certificate client) {
                List<String> headers = exchange.getRequestHeaders().get(BINDING_HEADER);
                return headers == null ? null : headers.get(0);
            }
        }), "case 2", "it MUST answer 403 when the header is missing");
    }

    /** One tool for all bindings: data and state are not kept per binding. */
    @Test
    public void withOneToolForEveryBindingTheTemplateFailsOnlyCase3() throws Exception {
        InMemoryTool[] shared = new InMemoryTool[1];
        failsExactly(new Variant(PinStore::ofDirectory, settings -> {
            shared[0] = new InMemoryTool();
            return new RemoteService(settings) {
                @Override
                protected InMemoryTool toolOf(String binding) {
                    return shared[0];
                }
            };
        }), "case 3", "the other binding created");
    }

    /**
     * Two layers carry case 1: when the handshake no longer requires the
     * certificate, the check per request refuses a request without one (401)
     * and the pin store refuses an unpinned one at the handshake, so the
     * template still follows the protocol.
     */
    @Test
    public void withoutRequiringTheCertificateAtTheHandshakeTheRequestCheckStillRefuses() throws Exception {
        passesEveryCase(new Variant(PinStore::ofDirectory, settings -> new RemoteService(settings) {
            @Override
            protected void configureTls(SSLParameters parameters, Set<String> supportedCipherSuites) {
                super.configureTls(parameters, supportedCipherSuites);
                parameters.setWantClientAuth(true);
            }
        }));
    }

    /** A last page that still names a cursor breaks an invariant of a page (lastPage=true exactly without nextCursor). */
    @Test
    public void aLastPageWithACursorIsAViolation() throws Exception {
        failsWith(page -> {
            if (Boolean.TRUE.equals(page.get("lastPage"))) {
                page.put("nextCursor", "after-the-end");
            }
        }, "answered its last page with a nextCursor");
    }

    /** A page that counts fewer elements than it delivers breaks the other invariant of a page. */
    @Test
    public void aPageThatCountsFewerElementsThanItDeliversIsAViolation() throws Exception {
        failsWith(page -> {
            if (!((List<?>) page.get("elements")).isEmpty()) {
                page.put("readCount", 0L);
            }
        }, "readCount is at least the number of elements");
    }

    /** The template with artifact pages changed by {@code change}: some case fails, and every failure names {@code fragment}. */
    private static void failsWith(Consumer<Map<String, Object>> change, String fragment) throws Exception {
        Verdict verdict = verdict(new Variant(PinStore::ofDirectory, settings -> new RemoteService(settings) {
            private final Map<String, InMemoryTool> tools = new ConcurrentHashMap<>();

            @Override
            protected InMemoryTool toolOf(String binding) {
                return tools.computeIfAbsent(binding, created -> new InMemoryTool() {
                    @Override
                    public synchronized Map<String, Object> readArtifacts(Map<String, Object> request) {
                        Map<String, Object> page = super.readArtifacts(request);
                        change.accept(page);
                        return page;
                    }
                });
            }
        }));

        assertFalse(verdict.violations().isEmpty(), "a case reads artifacts and must fail");
        verdict.violations().values().forEach(message -> assertTrue(message.contains(fragment), message));
        assertEquals(Set.of(), verdict.skipped().keySet(), verdict.skipped().toString());
    }
}
