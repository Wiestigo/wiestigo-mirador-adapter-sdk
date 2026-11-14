package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterConventions;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.RateLimit;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.tck.FakeHttpTool;
import com.rmm.adapter.tck.TckAdapterContext;
import com.rmm.adapter.tck.TckAdapterHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the TCK retries by the reserved key {@code http.retries} as the host does. When the adapter
 * declares the key, the TCK's HTTP path makes {@code 1 + min(http.retries, RateLimit.maxRetries, 3)}
 * attempts, the same number as the host; without the declaration a value has no effect. The TCK's
 * configuration accepts the reserved keys as the host does: only {@code INTEGER} in scope {@code BINDING},
 * at most their {@code .max}. It accepts {@code http.rate.per.second} too, but the TCK does not throttle;
 * only the host enforces the rate.
 */
public class TestTckRetriesFollowTheKey {

    private static final String RETRIES = AdapterConventions.HTTP_RETRIES_KEY;
    private static final String RATE = AdapterConventions.HTTP_RATE_PER_SECOND_KEY;
    private static final ConfigKey RETRIES_KEY = ConfigKey.optional(RETRIES, ConfigType.INTEGER, "",
            "Retries of a repeatable request of this connection");
    private static final ConfigKey RATE_KEY = ConfigKey.optional(RATE, ConfigType.INTEGER, "",
            "Requests per second of this connection");

    private FakeHttpTool tool;
    private final List<TckAdapterContext> contexts = new ArrayList<>();

    @BeforeEach
    public void unavailableTool() throws Exception {
        //Always 503 without Retry-After: every attempt arrives until the retries are exhausted
        tool = FakeHttpTool.startWithoutBindingHeader().route("/unavailable",
                exchange -> FakeHttpTool.respond(exchange, 503, "text/plain", new byte[0]));
    }

    @AfterEach
    public void release() {
        contexts.forEach(TckAdapterContext::release);
        tool.close();
    }

    @Test
    public void theConfiguredSingleRetryMeansTwoAttempts() throws Exception {
        assertEquals(2, attempts(descriptor(new RateLimit(40, 3), RETRIES_KEY), Map.of(RETRIES, "1"), Map.of()));
    }

    @Test
    public void zeroRetriesMeanOneAttempt() throws Exception {
        assertEquals(1, attempts(descriptor(new RateLimit(40, 3), RETRIES_KEY), Map.of(RETRIES, "0"), Map.of()));
    }

    @Test
    public void theKeyNeverRaisesTheRetriesAboveTheRateLimitOrThree() throws Exception {
        assertEquals(3, attempts(descriptor(new RateLimit(40, 2), RETRIES_KEY), Map.of(RETRIES, "9"), Map.of()),
                "at most the RateLimit of the descriptor");
        assertEquals(4, attempts(descriptor(null, RETRIES_KEY), Map.of(RETRIES, "9"), Map.of()),
                "without a RateLimit at most three retries, as in the host");
        assertEquals(3, attempts(descriptor(null, RETRIES_KEY), Map.of(RETRIES, "2"), Map.of()));
    }

    @Test
    public void withoutAValueTheRateLimitDecides() throws Exception {
        assertEquals(3, attempts(descriptor(new RateLimit(40, 2), RETRIES_KEY), Map.of(), Map.of()));
    }

    @Test
    public void anUndeclaredKeyIsIgnored() throws Exception {
        assertEquals(4, attempts(descriptor(new RateLimit(40, 3)), Map.of(RETRIES, "0"), Map.of()),
                "the value only takes effect when the adapter declares the key");
    }

    @Test
    public void thePlatformCapsTheKey() throws Exception {
        ConfigKey cap = ConfigKey.platform(RETRIES + ".max", ConfigType.INTEGER, "3", "Operator cap");
        AdapterDescriptor descriptor = descriptor(new RateLimit(40, 3), RETRIES_KEY, cap);

        assertEquals(2, attempts(descriptor, Map.of(RETRIES, "1"), Map.of(RETRIES + ".max", "1")));
        IllegalArgumentException above = assertThrows(IllegalArgumentException.class,
                () -> context(descriptor, Map.of(RETRIES, "2"), Map.of(RETRIES + ".max", "1")));
        assertTrue(above.getMessage().contains("above the platform limit"), above.getMessage());
        assertThrows(IllegalArgumentException.class, () -> context(descriptor, Map.of(RETRIES + ".max", "5"),
                Map.of()), "only the operator sets a cap");
    }

    @Test
    public void aValueThatIsNoLimitIsRefused() {
        AdapterDescriptor descriptor = descriptor(new RateLimit(40, 3), RETRIES_KEY);
        assertThrows(IllegalArgumentException.class, () -> context(descriptor, Map.of(RETRIES, "-1"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> context(descriptor, Map.of(RETRIES, "twice"), Map.of()));
    }

    @Test
    public void aReservedKeyDeclaredOtherwiseIsRefusedLikeInTheHost() {
        for (ConfigKey wrong : List.of(
                ConfigKey.optional(RETRIES, ConfigType.STRING, "", "retries as text"),
                ConfigKey.platform(RETRIES, ConfigType.INTEGER, "1", "retries of the operator"),
                ConfigKey.optional(RATE, ConfigType.BOOLEAN, "true", "rate as a flag"))) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> context(descriptor(new RateLimit(40, 3), wrong), Map.of(), Map.of()), wrong.toString());
            assertTrue(refused.getMessage().contains("<" + wrong.key() + "> is reserved")
                    && refused.getMessage().contains("INTEGER in scope BINDING"), refused.getMessage());
        }
        ConfigKey wrongCap = ConfigKey.optional(RETRIES + ".max", ConfigType.INTEGER, "3", "cap of the tenant");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> context(descriptor(new RateLimit(40, 3), RETRIES_KEY, wrongCap), Map.of(), Map.of()));
        assertTrue(refused.getMessage().contains("INTEGER in scope PLATFORM"), refused.getMessage());
    }

    /** The TCK accepts the rate (type and {@code .max} as in the host) but does not throttle. */
    @Test
    public void theRateKeyIsAcceptedButNotThrottled() throws Exception {
        ConfigKey cap = ConfigKey.platform(RATE + ".max", ConfigType.INTEGER, "20", "Operator cap");
        AdapterDescriptor descriptor = descriptor(new RateLimit(40, 3), RATE_KEY, cap, RETRIES_KEY);
        TckAdapterContext context = context(descriptor, Map.of(RATE, "5", RETRIES, "1"), Map.of());

        assertEquals(5, context.config().limit(RATE));
        assertThrows(IllegalArgumentException.class, () -> context(descriptor, Map.of(RATE, "50"), Map.of()),
                "above .max");
        assertEquals(2, attempts(context), "the retries still follow http.retries");
    }

    /**
     * What the host rejects when it loads the type, or refuses to bind with, the TCK refuses when it builds
     * the context. The same check applies to every cap.
     */
    @Test
    public void whatTheHostRefusesAtLoadOrBindTheTckRefusesToBuild() {
        RateLimit tool = new RateLimit(40, 3);
        //A negative rate value: the host does not bind, and the administration does not store it
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> context(descriptor(tool, RATE_KEY), Map.of(RATE, "-1"), Map.of()), "a negative rate value");
        assertTrue(negative.getMessage().contains("must not be negative"), negative.getMessage());
        //The adapter's own default for the rate is not a number: in the host the type is defective
        IllegalArgumentException fast = assertThrows(IllegalArgumentException.class,
                () -> context(descriptor(tool, ConfigKey.optional(RATE, ConfigType.INTEGER, "fast", "rate")),
                        Map.of(), Map.of()), "a default rate that is not a number");
        assertTrue(fast.getMessage().contains("<" + RATE + "> is reserved")
                && fast.getMessage().contains("default <fast>"), fast.getMessage());
        //The default of http.rate.per.second.max is not a number: in the host the type is defective at load
        IllegalArgumentException rateCap = assertThrows(IllegalArgumentException.class,
                () -> context(descriptor(tool, RATE_KEY, ConfigKey.platform(RATE + ".max", ConfigType.INTEGER, "abc",
                        "Operator cap")), Map.of(RATE, "5"), Map.of()), "a default rate cap that is not a number");
        assertTrue(rateCap.getMessage().contains("default <abc>"), rateCap.getMessage());
        //The same for http.retries.max, even without a value of the connection
        IllegalArgumentException retriesCap = assertThrows(IllegalArgumentException.class,
                () -> context(descriptor(tool, RETRIES_KEY, ConfigKey.platform(RETRIES + ".max", ConfigType.INTEGER,
                        "abc", "Operator cap")), Map.of(), Map.of()), "a default retries cap that is not a number");
        assertTrue(retriesCap.getMessage().contains("default <abc>"), retriesCap.getMessage());

        //The same check for every cap and every reserved key, as when the host loads the type
        assertThrows(IllegalArgumentException.class, () -> context(descriptor(tool,
                ConfigKey.optional("page.size", ConfigType.INTEGER, "50", "page"),
                ConfigKey.platform("page.size.max", ConfigType.INTEGER, "-1", "cap")), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> context(descriptor(tool,
                ConfigKey.optional("page.size", ConfigType.STRING, "50", "page"),
                ConfigKey.platform("page.size.max", ConfigType.INTEGER, "100", "cap")), Map.of(), Map.of()),
                "a cap of a key that is not INTEGER");
        assertThrows(IllegalArgumentException.class, () -> context(descriptor(tool,
                ConfigKey.optional(RETRIES, ConfigType.INTEGER, "twice", "retries")), Map.of(RETRIES, "1"), Map.of()),
                "a wrong default even with a value of the connection");
        assertThrows(IllegalArgumentException.class, () -> context(descriptor(tool, RATE_KEY), Map.of(RATE, "fast"),
                Map.of()), "a rate value that is not a number");
        assertThrows(IllegalArgumentException.class, () -> context(descriptor(tool, RATE_KEY,
                ConfigKey.platform(RATE + ".max", ConfigType.INTEGER, "20", "cap")), Map.of(RATE, "5"),
                Map.of(RATE + ".max", "many")), "an unreadable cap in the deployment, read with a value");

        //What the host loads and binds, the TCK accepts: an empty default of the cap without a value, 0 as rate
        assertDoesNotThrow(() -> context(descriptor(tool, RATE_KEY, ConfigKey.platform(RATE + ".max",
                ConfigType.INTEGER, "", "cap")), Map.of(), Map.of()));
        assertEquals(0, context(descriptor(tool, RATE_KEY), Map.of(RATE, "0"), Map.of()).config().limit(RATE));
    }

    /**
     * A default declared by the adapter holds like a value when the connection sets none. A default below
     * the {@link RateLimit} lowers the number of attempts.
     */
    @Test
    public void aDeclaredDefaultWithoutAValueOfTheConnectionHolds() throws Exception {
        ConfigKey oneRetry = ConfigKey.optional(RETRIES, ConfigType.INTEGER, "1", "Retries of this connection");
        assertEquals(2, attempts(descriptor(new RateLimit(40, 3), oneRetry), Map.of(), Map.of()),
                "the default 1: two attempts");
        assertEquals(4, attempts(descriptor(new RateLimit(40, 3), oneRetry), Map.of(RETRIES, "3"), Map.of()),
                "the value of the connection takes precedence");

        //Defaults 40 and 3 with a platform cap on the rate
        AdapterDescriptor capped = descriptor(new RateLimit(40, 3),
                ConfigKey.optional(RATE, ConfigType.INTEGER, "40", "Requests per second"),
                ConfigKey.platform(RATE + ".max", ConfigType.INTEGER, "40", "Operator cap"),
                ConfigKey.optional(RETRIES, ConfigType.INTEGER, "3", "Retries"));
        TckAdapterContext context = context(capped, Map.of(), Map.of(RATE + ".max", "30"));
        assertEquals(30, context.config().limit(RATE), "the default 40, cut to the .max of the deployment");
        assertEquals(4, attempts(context));
        //Only a default rate of 50
        assertEquals(50, context(descriptor(new RateLimit(50, 3),
                ConfigKey.optional(RATE, ConfigType.INTEGER, "50", "Requests per second")), Map.of(), Map.of())
                .config().limit(RATE));
    }

    /**
     * Settings of the HTTP path with fewer retries ({@code Builder.network}) also hold with a
     * {@link RateLimit} and with {@code http.retries}: never more attempts than the host would make.
     */
    @Test
    public void fewerRetriesOfTheNetworkSettingsHoldWithARateLimitToo() throws Exception {
        TckAdapterHttp.Settings oneRetry = new TckAdapterHttp.Settings(Duration.ofSeconds(120), 32 * 1024 * 1024, 1,
                TckAdapterHttp.Settings.SHORTENED_WAIT);
        assertEquals(2, attempts(context(descriptor(null), Map.of(), Map.of(), oneRetry)), "without a RateLimit");
        assertEquals(2, attempts(context(descriptor(new RateLimit(40, 3)), Map.of(), Map.of(), oneRetry)),
                "with a RateLimit");
        assertEquals(2, attempts(context(descriptor(new RateLimit(40, 3), RETRIES_KEY), Map.of(RETRIES, "2"),
                Map.of(), oneRetry)), "with a value of the connection above the settings");
        assertEquals(1, attempts(context(descriptor(new RateLimit(40, 3), RETRIES_KEY), Map.of(RETRIES, "0"),
                Map.of(), oneRetry)), "the smaller value of the connection");
        assertEquals(4, attempts(context(descriptor(new RateLimit(40, 3)), Map.of(), Map.of(),
                TckAdapterHttp.Settings.defaults())), "the defaults: exactly like the host");
    }

    // ------------------------------------------------------------------

    private int attempts(AdapterDescriptor descriptor, Map<String, String> connection, Map<String, String> platform)
            throws Exception {
        return attempts(context(descriptor, connection, platform));
    }

    /** A GET to the tool, which only answers 503: the last response goes to the adapter. */
    private int attempts(TckAdapterContext context) throws Exception {
        int before = tool.requests().size();
        SyncRunContext run = new SyncRunContext(UUID.randomUUID(), "RET-PRJ", Instant.now().plus(Duration.ofMinutes(2)));
        AdapterResponse response = context.inRun(run, () -> context.http().send(AdapterRequest.get(
                tool.uri("/unavailable"))));
        assertEquals(503, response.status());
        int attempts = tool.requests().size() - before;
        assertEquals(attempts, context.httpRequests(), "every attempt counts");
        return attempts;
    }

    private TckAdapterContext context(AdapterDescriptor descriptor, Map<String, String> connection,
                                      Map<String, String> platform) {
        return context(descriptor, connection, platform, TckAdapterHttp.Settings.defaults());
    }

    private TckAdapterContext context(AdapterDescriptor descriptor, Map<String, String> connection,
                                      Map<String, String> platform, TckAdapterHttp.Settings network) {
        TckAdapterContext context = TckAdapterContext.builder(descriptor).configuration(connection).platform(platform)
                .network(network).build();
        contexts.add(context);
        return context;
    }

    private static AdapterDescriptor descriptor(RateLimit rateLimit, ConfigKey... keys) {
        return new AdapterDescriptor("retrytool", "Retry tool", "1.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT, false, false, false, false,
                        SyncPhase.artifactsOnly(), Set.of(), Set.of(), rateLimit), Arrays.asList(keys), List.of());
    }
}
