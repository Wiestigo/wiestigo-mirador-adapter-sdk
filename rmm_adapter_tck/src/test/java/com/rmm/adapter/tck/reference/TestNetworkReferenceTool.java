package com.rmm.adapter.tck.reference;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SecretValue;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.tck.FakeHttpTool;
import com.rmm.adapter.tck.TckAdapterContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The network reference adapter and its fake tool where the contract cases do not reach: answers of the tool
 * and stored texts that are not what the adapter expects fail with an {@link AdapterException}, and the fake
 * tool answers a request it cannot read with 400.
 */
public class TestNetworkReferenceTool {

    @Test
    public void aUnicodeEscapeIsExactlyFourHexadecimalDigits() throws AdapterException {
        assertEquals("A\u00e9\u20ac!", NetworkToolAdapter.unquoted("\\u0041\\u00e9\\u20AC!"));
        assertEquals("tab\there \"quoted\" \\ /", NetworkToolAdapter.unquoted("tab\\there \\\"quoted\\\" \\\\ \\/"));

        for (String invalid : List.of("\\u+041", "\\u-041", "\\u 041", "\\u00", "\\u", "\\u004g", "\\uzzzz",
                "\\u\uff10\uff10\uff14\uff11")) {
            AdapterException failed = assertThrows(AdapterException.class,
                    () -> NetworkToolAdapter.unquoted("x" + invalid), invalid);

            assertTrue(failed.getMessage().contains("four hexadecimal digits (at index 1)"), failed.getMessage());
        }
    }

    @Test
    public void whatIsQuotedReadsBackUnchanged() throws AdapterException {
        for (String text : List.of("", "plain", "Label \"F-1\" \\ part", "line\nbreak\ttab\r", "\u0001\u001f",
                "\\u0041 stays text")) {
            assertEquals(text, NetworkToolAdapter.unquoted(
                    NetworkToolAdapter.quoted(text).substring(1, NetworkToolAdapter.quoted(text).length() - 1)));
        }
    }

    /** A tool that states a total the adapter cannot count with: the read fails, it does not crash. */
    @Test
    public void aTotalThatIsNoCountFailsTheReadWithAnAdapterException() throws Exception {
        for (String total : List.of("many", "-1", "1e3")) {
            try (FakeHttpTool tool = FakeHttpTool.start()) {
                tool.route("/space/rows", exchange -> {
                    exchange.getResponseHeaders().set("X-Total", total);
                    FakeHttpTool.respond(exchange, 200, "text/plain", "R-1".getBytes(StandardCharsets.UTF_8));
                });
                NetworkToolAdapter adapter = new NetworkToolAdapter();
                TckAdapterContext context = TckAdapterContext.of(adapter.descriptor(),
                        Map.of("tool.url", tool.uri("/space").toString()));
                AdapterInstance instance = adapter.bind(context);
                try {
                    SyncRunContext run = new SyncRunContext(UUID.randomUUID(), "NET-PRJ");
                    AdapterException failed = assertThrows(AdapterException.class, () -> context.inRun(run,
                            () -> instance.reader().orElseThrow().readArtifacts(run, ReadScope.full(), null)), total);

                    assertTrue(failed.getMessage().contains("X-Total <" + total + ">"), failed.getMessage());
                    assertFalse(failed.isRetryable());
                } finally {
                    instance.close();
                    context.release();
                }
            }
        }
    }

    /** A cursor that is no offset of the tool fails the read; it does not crash and sends nothing. */
    @Test
    public void aCursorThatIsNoOffsetFailsTheReadWithAnAdapterException() throws Exception {
        try (NetworkTool tool = NetworkTool.start()) {
            NetworkToolAdapter adapter = new NetworkToolAdapter();
            TckAdapterContext context = TckAdapterContext.of(adapter.descriptor(),
                    Map.of("tool.url", tool.address(NetworkTool.FIRST).toString(),
                            "tool.token", tool.token(NetworkTool.FIRST)));
            AdapterInstance instance = adapter.bind(context);
            try {
                SyncRunContext run = new SyncRunContext(UUID.randomUUID(), "NET-PRJ");
                for (String foreign : List.of("page-2", "1.5", "-2", "99999999999")) {
                    AdapterException failed = assertThrows(AdapterException.class, () -> context.inRun(run,
                            () -> instance.reader().orElseThrow().readArtifacts(run, ReadScope.full(),
                                    new PageCursor(foreign))), foreign);

                    assertTrue(failed.getMessage().contains("<" + foreign + ">"), failed.getMessage());
                    assertFalse(failed.isRetryable(), "the same cursor fails again: " + foreign);
                }
            } finally {
                instance.close();
                context.release();
            }
        }
    }

    @Test
    public void theFakeToolAnswersAnOffsetOrLimitItCannotReadWith400() throws Exception {
        try (NetworkTool tool = NetworkTool.start()) {
            TckAdapterContext context = TckAdapterContext.of(new NetworkToolAdapter().descriptor(),
                    Map.of("tool.url", tool.address(NetworkTool.FIRST).toString()));
            try {
                URI rows = URI.create(tool.address(NetworkTool.FIRST) + "/rows");
                context.http().auth(URI.create(rows.getScheme() + "://" + rows.getRawAuthority()),
                        Auth.bearer(SecretValue.of(tool.token(NetworkTool.FIRST))));

                for (String query : List.of("offset=two", "offset=-1", "limit=", "limit=-5", "offset=1&limit=x",
                        "offset=99999999999")) {
                    assertEquals(400, context.http().send(AdapterRequest.get(URI.create(rows + "?" + query)))
                            .status(), query);
                }
                assertEquals("F-2\nF-3", context.http().send(AdapterRequest.get(URI.create(rows
                        + "?offset=1&limit=2"))).bodyAsString());
                assertEquals("F-4\nF-5", context.http().send(AdapterRequest.get(URI.create(rows
                        + "?offset=3&limit=2147483647"))).bodyAsString(), "a limit beyond the rows ends at the last");
                assertEquals("", context.http().send(AdapterRequest.get(URI.create(rows + "?offset=9")))
                        .bodyAsString(), "an offset beyond the rows is an empty page");
            } finally {
                context.release();
            }
        }
    }
}
