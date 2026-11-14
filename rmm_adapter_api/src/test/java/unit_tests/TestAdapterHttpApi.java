package unit_tests;

import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.Body;
import com.rmm.adapter.api.Multipart;
import com.rmm.adapter.api.RedirectPolicy;
import com.rmm.adapter.api.SecretValue;
import com.rmm.adapter.api.StreamResponse;
import com.rmm.adapter.api.TokenProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The types of the host's HTTP path ({@code AdapterHttp}): bodies that are
 * readable once and have a checked length, multipart, the redirect rule
 * (maximum number of hops, never from https to http, a request with a body
 * never follows), the default for "idempotent" by method and body, headers
 * that carry credentials, and URLs that appear in messages without their
 * query.
 */
class TestAdapterHttpApi {

    private static final URI TOOL = URI.create("https://tool.test/rest/api");
    private static final RedirectPolicy FOLLOW = RedirectPolicy.follow(3);

    private static Body stream(int length) {
        return Body.ofStream(length, null, () -> new ByteArrayInputStream(new byte[length]));
    }

    private static AdapterRequest following(String method, Body body) {
        AdapterRequest.Builder builder = AdapterRequest.builder(method, TOOL).redirects(FOLLOW);
        return (body == null ? builder : builder.body(body)).build();
    }

    // --- Body -------------------------------------------------------------

    @Test
    void aStreamBodyIsReadableOnlyOnce() throws IOException {
        AtomicInteger opened = new AtomicInteger();
        Body body = Body.ofStream(3, "text/plain", () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8));
        });

        assertFalse(body.replayable());
        assertEquals(0, opened.get(), "only the host opens the source, when it sends");
        try (InputStream in = body.open()) {
            assertEquals("abc", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThrows(IllegalStateException.class, body::open);
        assertEquals(1, opened.get());
    }

    @Test
    void aByteBodyIsReadableAnyNumberOfTimesAndCopied() throws IOException {
        byte[] bytes = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        Body body = Body.ofBytes(bytes, "application/json");
        bytes[0] = 'X';

        assertTrue(body.replayable());
        assertEquals(7, body.length());
        for (int i = 0; i < 2; i++) {
            try (InputStream in = body.open()) {
                assertEquals("{\"a\":1}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals("application/json", Body.ofJson("{}").contentType().orElseThrow());
    }

    @Test
    void aNegativeLengthIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Body.ofStream(-1, null, () -> new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void theStreamDeliversExactlyTheDeclaredLength() {
        Body tooShort = Body.ofStream(5, null, () -> new ByteArrayInputStream(new byte[3]));
        IOException shortFailure = assertThrows(IOException.class, () -> tooShort.open().readAllBytes());
        assertTrue(shortFailure.getMessage().contains("3 of 5"), shortFailure.getMessage());

        Body tooLong = Body.ofStream(2, null, () -> new ByteArrayInputStream(new byte[4]));
        assertThrows(IOException.class, () -> tooLong.open().readAllBytes());
    }

    // --- Multipart --------------------------------------------------------

    @Test
    void multipartBuildsFieldsAndFilesWithMatchingLength() throws IOException {
        AtomicInteger opened = new AtomicInteger();
        byte[] file = "PDF-BYTES".getBytes(StandardCharsets.US_ASCII);
        Body body = Multipart.builder()
                .field("comment", "Grüße")
                .file("file", "spec \"v2\".pdf", Body.ofStream(file.length, "application/pdf", () -> {
                    opened.incrementAndGet();
                    return new ByteArrayInputStream(file);
                }))
                .build();

        String contentType = body.contentType().orElseThrow();
        assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType);
        String boundary = contentType.substring(contentType.indexOf('=') + 1);
        assertFalse(body.replayable(), "with a stream part readable only once");
        assertEquals(0, opened.get(), "the file is opened only when it is read");

        byte[] bytes;
        try (InputStream in = body.open()) {
            bytes = in.readAllBytes();
        }
        assertEquals(body.length(), bytes.length);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertEquals("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"comment\"\r\n\r\n"
                + "Grüße\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"spec %22v2%22.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n"
                + "PDF-BYTES\r\n"
                + "--" + boundary + "--\r\n", text);
        assertThrows(IllegalStateException.class, body::open);
    }

    @Test
    void multipartOfBytesOnlyIsReplayable() throws IOException {
        Body body = Multipart.builder().file("f", "a.bin", Body.ofBytes(new byte[]{1, 2}, null)).build();

        assertTrue(body.replayable());
        assertArrayEquals(body.open().readAllBytes(), body.open().readAllBytes());
        assertTrue(new String(body.open().readAllBytes(), StandardCharsets.UTF_8)
                .contains("Content-Type: application/octet-stream"));
        assertThrows(IllegalStateException.class, () -> Multipart.builder().build());
        assertThrows(IllegalArgumentException.class, () -> Multipart.builder().field(" ", "x"));
    }

    // --- Redirects --------------------------------------------------------

    @Test
    void redirectsFollowAtMostMaxHops() {
        AdapterRequest twice = AdapterRequest.builder("GET", TOOL).redirects(RedirectPolicy.follow(2)).build();
        URI from = URI.create("https://tool.test/a");
        URI to = URI.create("https://tool.test/b");

        assertTrue(RedirectPolicy.refusal(twice, 302, from, to, 1).isEmpty());
        assertTrue(RedirectPolicy.refusal(twice, 302, from, to, 2).isEmpty());
        assertTrue(RedirectPolicy.refusal(twice, 302, from, to, 3).orElseThrow().contains("too many redirects"));
        assertTrue(RedirectPolicy.refusal(AdapterRequest.get(TOOL), 302, from, to, 1).isPresent());
        assertEquals(RedirectPolicy.never(), AdapterRequest.get(TOOL).redirects(), "default: never follow");

        assertThrows(IllegalArgumentException.class, () -> RedirectPolicy.follow(0));
        assertThrows(IllegalArgumentException.class, () -> RedirectPolicy.follow(RedirectPolicy.MAX_HOPS + 1));
        assertThrows(IllegalArgumentException.class, () -> RedirectPolicy.refusal(twice, 200, from, to, 1));
    }

    @Test
    void neverFromHttpsToHttp() {
        AdapterRequest get = following("GET", null);

        assertTrue(RedirectPolicy.refusal(get, 301, URI.create("https://tool.test/a"), URI.create("http://tool.test/a"), 1)
                .orElseThrow().contains("https to http"));
        assertTrue(RedirectPolicy.refusal(get, 301, URI.create("http://tool.test/a"), URI.create("https://tool.test/a"), 1)
                .isEmpty());
        assertTrue(RedirectPolicy.refusal(get, 302, TOOL, URI.create("/relative"), 1).isPresent(),
                "the host resolves Location beforehand");
        assertTrue(RedirectPolicy.refusal(get, 302, TOOL, URI.create("ftp://tool.test/x"), 1).isPresent());
    }

    /** Uploads never follow: no hop with a body, whatever the status, the target and the body. */
    @Test
    void aRequestWithABodyFollowsNoRedirect() {
        URI sameOrigin = URI.create("https://tool.test/upload2");
        URI otherOrigin = URI.create("https://evil.test/collect");
        List<Body> bodies = List.of(Body.ofJson("{\"password\":\"x\"}"), stream(1),
                Multipart.builder().field("a", "b").build());

        for (Body body : bodies) {
            for (String method : List.of("POST", "PUT", "PATCH")) {
                AdapterRequest request = following(method, body);
                for (int status : List.of(301, 302, 303, 307, 308)) {
                    for (URI to : List.of(sameOrigin, otherOrigin)) {
                        String refusal = RedirectPolicy.refusal(request, status, TOOL, to, 1)
                                .orElseThrow(() -> new AssertionError(method + " " + body + " " + status + " " + to));
                        assertTrue(refusal.contains("with a body"), refusal);
                    }
                }
            }
        }
    }

    @Test
    void withoutABodyTheRequestFollowsWithoutChangingTheMethod() {
        URI to = URI.create("https://cdn.tool.test/file");

        for (int status : List.of(301, 302, 303, 307, 308)) {
            assertTrue(RedirectPolicy.refusal(following("GET", null), status, TOOL, to, 1).isEmpty(), "GET " + status);
            assertTrue(RedirectPolicy.refusal(following("HEAD", null), status, TOOL, to, 1).isEmpty(), "HEAD " + status);
        }
        assertTrue(RedirectPolicy.refusal(following("DELETE", null), 307, TOOL, to, 1).isEmpty());
        assertTrue(RedirectPolicy.refusal(following("DELETE", null), 303, TOOL, to, 1).orElseThrow()
                .contains("change the method"), "303 would turn DELETE into GET");
    }

    @Test
    void sameOriginWithDefaultPortAndIgnoringCase() {
        assertTrue(RedirectPolicy.sameOrigin(URI.create("https://Tool.test/a"), URI.create("https://tool.test:443/b")));
        assertTrue(RedirectPolicy.sameOrigin(URI.create("http://tool.test/a"), URI.create("http://tool.test:80/")));
        assertTrue(RedirectPolicy.sameOrigin(URI.create("http://jira_server:8080/a"), URI.create("http://JIRA_server:8080/")));
        assertFalse(RedirectPolicy.sameOrigin(URI.create("http://jira_server:8080/"), URI.create("http://jira_server/")));
        assertFalse(RedirectPolicy.sameOrigin(URI.create("https://tool.test/"), URI.create("https://tool.test:8443/")));
        assertFalse(RedirectPolicy.sameOrigin(URI.create("https://tool.test/"), URI.create("http://tool.test/")));
        assertFalse(RedirectPolicy.sameOrigin(URI.create("https://tool.test/"), URI.create("https://cdn.tool.test/")));
    }

    // --- Request ----------------------------------------------------------

    @Test
    void idempotentFollowsTheMethodByDefault() {
        Body json = Body.ofJson("{}");

        assertTrue(AdapterRequest.get(TOOL).idempotent());
        assertTrue(AdapterRequest.head(TOOL).idempotent());
        assertTrue(AdapterRequest.put(TOOL, json).idempotent());
        assertTrue(AdapterRequest.put(TOOL, json).repeatable(), "the host may repeat a PUT with JSON");
        assertTrue(AdapterRequest.delete(TOOL).idempotent());
        assertTrue(AdapterRequest.builder("OPTIONS", TOOL).build().idempotent());
        assertFalse(AdapterRequest.post(TOOL, json).idempotent());
        assertFalse(AdapterRequest.builder("PATCH", TOOL).body(json).build().idempotent());
    }

    @Test
    void aReadingPostMayMarkItselfIdempotent() {
        AdapterRequest search = AdapterRequest.builder("post", TOOL).body(Body.ofJson("{}")).idempotent(true).build();

        assertEquals("POST", search.method());
        assertTrue(search.idempotent());
        assertTrue(search.repeatable());
        assertFalse(AdapterRequest.builder("GET", TOOL).idempotent(false).build().idempotent());
    }

    /** An upload is never repeated, not even as PUT and not with a multipart built from bytes. */
    @Test
    void anUploadIsNeverRepeatable() {
        Body bytesMultipart = Multipart.builder().file("file", "a.bin", Body.ofBytes(new byte[]{1}, null)).build();
        assertTrue(bytesMultipart.replayable(), "replayable, but still an upload");

        for (Body upload : List.of(stream(1), bytesMultipart)) {
            AdapterRequest put = AdapterRequest.put(TOOL, upload);
            assertFalse(put.idempotent(), "PUT with " + upload);
            assertFalse(put.repeatable(), "PUT with " + upload);
            assertFalse(AdapterRequest.post(TOOL, upload).idempotent());
            assertThrows(IllegalStateException.class,
                    () -> AdapterRequest.builder("PUT", TOOL).body(upload).idempotent(true).build());
        }
    }

    @Test
    void aCopyDerivesTheDefaultAnewButKeepsTheMark() {
        AdapterRequest json = AdapterRequest.put(TOOL, Body.ofJson("{}"));
        AdapterRequest asUpload = json.toBuilder().body(stream(1)).build();
        assertFalse(asUpload.idempotent(), "the default applies to the new body");

        AdapterRequest search = AdapterRequest.builder("POST", TOOL).body(Body.ofJson("{}")).idempotent(true).build();
        assertTrue(search.toBuilder().header("Accept", "application/json").build().idempotent());
    }

    @Test
    void theUrlIsAbsoluteHttpAndWithoutCredentials() {
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.get(URI.create("/rest/api")));
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.get(URI.create("ftp://tool.test/")));
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.get(URI.create("https://u:p@tool.test/")));
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.get(URI.create("http://u:p@jira_server/")));
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.get(URI.create("mailto:a@tool.test")));
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.builder("TRACE", TOOL));
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.builder("CONNECT", TOOL));
    }

    /** Container names with an underscore are allowed; international names must be in punycode. */
    @Test
    void containerNamesWithUnderscoreAndPunycodeAreAllowed() {
        AdapterRequest container = AdapterRequest.get(URI.create("http://jira_server:8080/rest"));
        assertEquals("jira_server", container.host());
        assertEquals(8080, container.port());
        assertEquals(80, AdapterRequest.get(URI.create("http://my_tool/")).port());
        assertEquals(443, AdapterRequest.get(TOOL).port());

        AdapterRequest punycode = AdapterRequest.get(URI.create("https://xn--bcher-kva.example/"));
        assertEquals("xn--bcher-kva.example", punycode.host());

        IllegalArgumentException unicode = assertThrows(IllegalArgumentException.class,
                () -> AdapterRequest.get(URI.create("https://bücher.example/")));
        assertTrue(unicode.getMessage().contains("punycode"), unicode.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.get(URI.create("http://a_b:99999/")));
        assertThrows(IllegalArgumentException.class, () -> AdapterRequest.get(URI.create("http://:80/")));
    }

    @Test
    void headersAreFreeExceptThoseOfTheTransport() {
        AdapterRequest request = AdapterRequest.builder("GET", TOOL)
                .header("Accept", "application/json")
                .header("X-Multi", "a")
                .header("x-multi", "b")
                .header("X-Tab", "a\tb")
                .header("X-Latin", "café")
                .header("Authorization", "Bearer abc")
                .timeout(Duration.ofSeconds(5))
                .build();

        assertEquals(List.of("a", "b"), request.headers().get("X-MULTI"));
        assertEquals("application/json", request.header("accept").orElseThrow());
        assertFalse(request.toString().contains("abc"), "toString without headers");
        assertEquals(Duration.ofSeconds(5), request.timeout().orElseThrow());

        AdapterRequest.Builder builder = AdapterRequest.builder("GET", TOOL);
        assertThrows(IllegalArgumentException.class, () -> builder.header("Host", "evil.test"));
        assertThrows(IllegalArgumentException.class, () -> builder.header("content-length", "1"));
        assertThrows(IllegalArgumentException.class, () -> builder.header("Transfer-Encoding", "chunked"));
        assertThrows(IllegalArgumentException.class, () -> builder.header("Accept-Encoding", "gzip"),
                "decompression and the size limit belong to the host");
        assertThrows(IllegalArgumentException.class, () -> builder.header("Content-Type", "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> builder.header("Bad Name", "1"));
        assertThrows(IllegalArgumentException.class, () -> builder.timeout(Duration.ZERO));
        assertThrows(IllegalStateException.class,
                () -> AdapterRequest.builder("GET", TOOL).body(Body.ofJson("{}")).build());
        for (String bad : List.of("1\r\nX-Injected: 2", "a\nb", "a\u0000b", "a\u000Bb", "a\u000Cb", "a\u007Fb",
                "a\u0085b", "aĀb")) {
            assertThrows(IllegalArgumentException.class, () -> builder.header("X-A", bad), bad);
        }
    }

    @Test
    void headersWithCredentialsAreMarked() {
        AdapterRequest request = AdapterRequest.builder("GET", TOOL)
                .header("Accept", "application/json")
                .header("User-Agent", "rmm")
                .header("Authorization", "Bearer abc")
                .header("Cookie", "JSESSIONID=1")
                .header("X-API-Key", "k")
                .header("PRIVATE-TOKEN", "t")
                .header("X-Auth-Token", "t")
                .secretHeader("X-Tool-Key", SecretValue.of("s3cr3t"))
                .build();

        assertEquals(Set.of("Authorization", "Cookie", "X-API-Key", "PRIVATE-TOKEN", "X-Auth-Token", "X-Tool-Key"),
                Set.copyOf(request.sensitiveHeaders()));
        assertTrue(request.sensitiveHeaders().contains("x-tool-key"), "case-insensitive");
        assertEquals("s3cr3t", request.header("X-Tool-Key").orElseThrow());
        assertTrue(request.toBuilder().build().sensitiveHeaders().contains("X-Tool-Key"), "the copy keeps the mark");
        assertTrue(AdapterRequest.get(TOOL).sensitiveHeaders().isEmpty());
    }

    /** Queries often carry secrets (api_key, signed URLs): no output shows them. */
    @Test
    void outputsDoNotShowTheQueryOfTheUrl() {
        URI signed = URI.create("https://bucket.test/file.pdf?X-Amz-Signature=SECRETQ&api_key=SECRETQ#frag");

        assertEquals("https://bucket.test/file.pdf?…", AdapterRequest.forLog(signed));
        assertEquals("https://tool.test/rest/api", AdapterRequest.forLog(TOOL));
        assertFalse(AdapterRequest.get(signed).toString().contains("SECRETQ"));
        assertFalse(new AdapterResponse(200, signed, Map.of(), new byte[0]).toString().contains("SECRETQ"));
        assertFalse(new StreamResponse(200, signed, Map.of(), OptionalLong.empty(), InputStream.nullInputStream())
                .toString().contains("SECRETQ"));
        assertTrue(AdapterRequest.get(signed).toString().contains("bucket.test/file.pdf"));
    }

    // --- Responses and authentication ------------------------------------

    @Test
    void responsesFindHeadersIgnoringCaseAndReadTheCharset() throws IOException {
        byte[] latin1 = "Größe".getBytes(StandardCharsets.ISO_8859_1);
        AdapterResponse response = new AdapterResponse(200, TOOL,
                Map.of("Content-Type", List.of("text/plain; charset=ISO-8859-1"), "Set-Cookie", List.of("a=1", "b=2")),
                latin1);

        assertTrue(response.successful());
        assertEquals("Größe", response.bodyAsString());
        assertEquals(List.of("a=1", "b=2"), response.headerValues("set-cookie"));
        assertArrayEquals(latin1, response.bodyStream().readAllBytes());
        assertThrows(IllegalArgumentException.class, () -> new AdapterResponse(99, TOOL, Map.of(), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new AdapterResponse(600, TOOL, Map.of(), new byte[0]));
    }

    @Test
    void closingTheResponseClosesTheBody() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new ByteArrayInputStream(new byte[10]) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        try (StreamResponse response = new StreamResponse(200, TOOL, Map.of(), OptionalLong.of(10), body)) {
            assertEquals(10, response.length().getAsLong());
            response.body().read();
        }
        //Aborting the transfer on close is the host's part; this checks only that close() is passed on
        assertTrue(closed.get(), "close() reaches the body");
        assertThrows(IllegalArgumentException.class, () -> new StreamResponse(200, TOOL, Map.of(),
                OptionalLong.of(-1), InputStream.nullInputStream()));
    }

    @Test
    void authNeverShowsSecrets() {
        SecretValue secret = SecretValue.of("s3cr3t");
        Auth basic = Auth.basic("user", secret);
        Auth bearer = Auth.bearer(secret);
        TokenProvider.Token token = new TokenProvider.Token(secret, null);

        assertFalse(basic.toString().contains("s3cr3t"), basic.toString());
        assertFalse(bearer.toString().contains("s3cr3t"), bearer.toString());
        assertFalse(token.toString().contains("s3cr3t"), token.toString());
        assertEquals("s3cr3t", secret.reveal());
        assertEquals("Jürgen Ковалёв", ((Auth.Basic) Auth.basic("Jürgen Ковалёв", secret)).user(),
                "account in UTF-8");
        assertThrows(IllegalArgumentException.class, () -> Auth.basic("us:er", secret));
        assertThrows(IllegalArgumentException.class, () -> Auth.basic("us\u0000er", secret));
        assertThrows(IllegalArgumentException.class, () -> Auth.bearer(SecretValue.of("a\r\nX: b")));
        assertThrows(IllegalArgumentException.class, () -> Auth.oauth(null));
    }

    /**
     * Basic authentication in a given charset: with two arguments the bytes are
     * UTF-8 (RFC 7617); the third argument sets the bytes before Base64 (a tool
     * such as Jama expects ISO-8859-1). What the charset cannot encode fails at
     * construction, without the value in the message.
     */
    @Test
    void basicEncodesInTheGivenCharset() {
        SecretValue umlauts = SecretValue.of("Grüße2026");
        Auth.Basic utf8 = (Auth.Basic) Auth.basic("jürgen", umlauts);
        Auth.Basic latin = (Auth.Basic) Auth.basic("jürgen", umlauts, StandardCharsets.ISO_8859_1);

        assertEquals(StandardCharsets.UTF_8, utf8.charset(), "two arguments: UTF-8");
        assertEquals(StandardCharsets.UTF_8, new Auth.Basic("jürgen", umlauts).charset());
        assertEquals(StandardCharsets.ISO_8859_1, latin.charset());
        assertEquals(Base64.getEncoder().encodeToString("jürgen:Grüße2026".getBytes(StandardCharsets.UTF_8)),
                utf8.token().reveal());
        assertEquals(Base64.getEncoder().encodeToString("jürgen:Grüße2026".getBytes(StandardCharsets.ISO_8859_1)),
                latin.token().reveal());
        assertNotEquals(utf8.token().reveal(), latin.token().reveal(), "non-ASCII characters: different bytes");
        Auth.Basic ascii = (Auth.Basic) Auth.basic("svc", SecretValue.of("plain"), StandardCharsets.ISO_8859_1);
        assertEquals(((Auth.Basic) Auth.basic("svc", SecretValue.of("plain"))).token().reveal(), ascii.token().reveal(),
                "ASCII is the same in both");
        assertFalse(latin.toString().contains("Grüße2026"), latin.toString());
        assertFalse(latin.toString().contains(latin.token().reveal()), latin.toString());
        assertFalse(latin.token().toString().contains(latin.token().reveal()), "the Base64 form is a secret");

        IllegalArgumentException password = assertThrows(IllegalArgumentException.class,
                () -> Auth.basic("svc", SecretValue.of("Ковалёв-secret"), StandardCharsets.ISO_8859_1));
        assertFalse(password.getMessage().contains("Ковалёв"), password.getMessage());
        assertTrue(password.getMessage().contains("ISO-8859-1"), password.getMessage());
        IllegalArgumentException user = assertThrows(IllegalArgumentException.class,
                () -> Auth.basic("Ковалёв", umlauts, StandardCharsets.US_ASCII));
        assertFalse(user.getMessage().contains("Ковалёв"), user.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Auth.basic("svc", umlauts, StandardCharsets.US_ASCII),
                "a non-ASCII character in US-ASCII");
        assertThrows(IllegalArgumentException.class, () -> Auth.basic("svc", umlauts, null));
        IllegalArgumentException surrogate = assertThrows(IllegalArgumentException.class,
                () -> Auth.basic("svc", SecretValue.of("x\uD800y")), "UTF-8 does not encode a lone surrogate");
        assertFalse(surrogate.getMessage().contains("x\uD800y"), surrogate.getMessage());
    }
}
