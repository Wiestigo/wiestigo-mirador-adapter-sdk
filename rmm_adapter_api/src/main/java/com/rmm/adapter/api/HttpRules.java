package com.rmm.adapter.api;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Shared rules of the HTTP types ({@link AdapterHttp}): addresses, headers and
 * charset. Package-private so that request, response, redirect policy and
 * configuration use the same checks and do not drift apart.
 */
final class HttpRules {

    /** Labels of letters, digits, hyphen and underscore, separated by dots. */
    private static final Pattern HOST_NAME = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*\\.?");
    private static final List<String> SENSITIVE_PARTS = List.of("auth", "token", "secret", "password", "session",
            "credential", "signature", "api-key", "api_key", "apikey");

    private HttpRules() {
    }

    /**
     * Checks an absolute {@code http} or {@code https} address with a host and
     * without credentials. Credentials in the address would bypass the rule
     * that {@code Authorization} goes only to the origin.
     *
     * <p>Host names with an underscore (container names such as
     * {@code jira_server}) are allowed although {@link URI#getHost()} returns
     * {@code null} for them; the authority is used instead ({@link #hostOf},
     * {@link #portOf}). Internationalized names only in Punycode
     * ({@code xn--…}); {@link AdapterConfig#url} converts Unicode beforehand.
     *
     * @return the problem, empty = fine; it never contains the value
     */
    static Optional<String> httpUriProblem(URI uri) {
        if (uri == null) {
            return Optional.of("is missing");
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return Optional.of("is not an absolute URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of("must use http or https");
        }
        String authority = uri.getRawAuthority();
        if (uri.getRawUserInfo() != null || (authority != null && authority.indexOf('@') >= 0)) {
            return Optional.of("must not contain credentials");
        }
        if (uri.getHost() != null) {
            return uri.getHost().isBlank() ? Optional.of("has no host") : Optional.empty();
        }
        // Authority that is not server-based: a name with an underscore, Unicode, or invalid
        if (authority == null || authority.isEmpty()) {
            return Optional.of("has no host");
        }
        int colon = authority.lastIndexOf(':');
        String host = colon >= 0 ? authority.substring(0, colon) : authority;
        if (host.isEmpty()) {
            return Optional.of("has no host");
        }
        if (!host.chars().allMatch(c -> c < 0x80)) {
            return Optional.of("must name an international host in its punycode form (xn--...)");
        }
        if (!HOST_NAME.matcher(host).matches()) {
            return Optional.of("has an invalid host name");
        }
        if (colon >= 0 && rawPort(authority.substring(colon + 1)) < 0) {
            return Optional.of("has an invalid port");
        }
        return Optional.empty();
    }

    /** Like {@link #httpUriProblem}, but throws, naming the checked input. */
    static URI requireHttpUri(URI uri, String what) {
        Optional<String> problem = httpUriProblem(uri);
        if (problem.isPresent()) {
            throw new IllegalArgumentException(what + " " + problem.get());
        }
        return uri;
    }

    /**
     * The host of a checked address in lower case, also for names with an
     * underscore, which {@link URI#getHost()} does not recognize; {@code null}
     * without a host. IPv6 in square brackets as with {@link URI#getHost()}.
     */
    static String hostOf(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            String authority = uri.getRawAuthority();
            if (authority == null || authority.indexOf('@') >= 0) {
                return null;
            }
            int colon = authority.lastIndexOf(':');
            host = colon >= 0 ? authority.substring(0, colon) : authority;
            if (host.isEmpty()) {
                return null;
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** The port of a checked address; if not given, 443 for {@code https}, otherwise 80. */
    static int portOf(URI uri) {
        int port = uri.getPort();
        if (port < 0 && uri.getHost() == null && uri.getRawAuthority() != null) {
            String authority = uri.getRawAuthority();
            int colon = authority.lastIndexOf(':');
            port = colon >= 0 ? rawPort(authority.substring(colon + 1)) : -1;
        }
        if (port >= 0) {
            return port;
        }
        return isHttps(uri) ? 443 : 80;
    }

    /** A port of digits between 0 and 65535; otherwise -1. */
    private static int rawPort(String digits) {
        if (digits.isEmpty() || digits.length() > 5 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        int port = Integer.parseInt(digits);
        return port <= 65535 ? port : -1;
    }

    static boolean isHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme());
    }

    /**
     * The address for logs and messages: scheme, host, port and path; a query
     * appears only as {@code ?…}, a fragment not at all. Queries often carry
     * secrets ({@code api_key=…}, signed addresses with
     * {@code X-Amz-Signature}).
     */
    static String forLog(URI uri) {
        if (uri == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        if (uri.getScheme() != null) {
            text.append(uri.getScheme()).append(':');
        }
        String authority = uri.getRawAuthority();
        if (authority != null) {
            // A checked address never contains credentials; they are dropped here to be safe
            text.append("//").append(authority.substring(authority.lastIndexOf('@') + 1));
        }
        if (uri.getRawPath() != null) {
            text.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            text.append("?…");
        }
        return text.toString();
    }

    /**
     * Converts a Unicode host to Punycode (IDN); every other address stays as
     * it is. The message of an error does not contain the value.
     */
    static URI asciiHost(URI uri) {
        String authority = uri.getRawAuthority();
        if (uri.getHost() != null || authority == null || authority.indexOf('@') >= 0
                || authority.chars().allMatch(c -> c < 0x80)) {
            return uri;
        }
        int colon = authority.lastIndexOf(':');
        String host = colon >= 0 ? authority.substring(0, colon) : authority;
        String port = colon >= 0 ? authority.substring(colon) : "";
        StringBuilder text = new StringBuilder(uri.getScheme()).append("://");
        try {
            text.append(IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("has an invalid international host name");
        }
        text.append(port);
        if (uri.getRawPath() != null) {
            text.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            text.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            text.append('#').append(uri.getRawFragment());
        }
        try {
            return new URI(text.toString());
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("has an invalid international host name");
        }
    }

    /** Whether the string is a token according to RFC 9110 (method, header name). */
    static boolean isToken(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alphaNumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphaNumeric && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * A header value: visible characters, space and tab up to {@code 0xFF}
     * (RFC 9110 5.5). No control character (CR, LF, NUL, VT, FF, DEL …), so
     * that no header can be smuggled in, and nothing that cannot be sent as a
     * single byte.
     */
    static boolean isHeaderValue(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isControl(c) || c > 0xFF) {
                return false;
            }
        }
        return true;
    }

    /** Text without control characters except tab; characters above {@code 0xFF} are allowed (a user in UTF-8). */
    static boolean hasNoControl(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (isControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isControl(char c) {
        return (c < 0x20 && c != '\t') || (c >= 0x7F && c <= 0x9F);
    }

    /**
     * Whether a header may carry credentials and is therefore removed when the
     * origin changes: {@code Cookie} and every name that contains {@code auth},
     * {@code token}, {@code secret}, {@code password}, {@code session},
     * {@code credential}, {@code signature}, {@code api-key}, {@code api_key}
     * or {@code apikey}, for example {@code Authorization},
     * {@code Proxy-Authorization}, {@code X-API-Key}, {@code PRIVATE-TOKEN},
     * {@code X-Auth-Token}.
     */
    static boolean isSensitiveHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("cookie")) {
            return true;
        }
        for (String part : SENSITIVE_PARTS) {
            if (lower.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies headers into an immutable map whose names are looked up
     * case-insensitively. Several values per name keep their order; a
     * {@code null} name (the status line of some clients) is dropped.
     */
    static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                copy.computeIfAbsent(entry.getKey(), name -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        copy.replaceAll((name, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(copy);
    }

    static Optional<String> firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /** The charset from {@code Content-Type}; UTF-8 if absent or unknown. */
    static Charset charsetOf(Optional<String> contentType) {
        if (contentType.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        for (String parameter : contentType.get().split(";")) {
            String trimmed = parameter.trim();
            if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                String name = trimmed.substring(8).replace("\"", "").trim();
                try {
                    return Charset.forName(name);
                } catch (RuntimeException unknown) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
