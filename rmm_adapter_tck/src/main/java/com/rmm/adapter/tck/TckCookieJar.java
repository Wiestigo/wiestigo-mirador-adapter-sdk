package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterRequest;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The cookie store of a binding in the TCK ({@link TckAdapterHttp#cookies()}),
 * with the same semantics as the host's store. The JDK's own store is not
 * used: it sends a cookie that has {@code Max-Age} in RFC 2965 format
 * ({@code pos="5";$Path="/api/"}) and matches the path {@code /api} against
 * {@code /apiary}.
 *
 * <ul>
 *   <li><b>Per origin</b> (scheme, host, port): a cookie goes only to the
 *       origin that set it; {@code Domain} widens nothing. This is stricter
 *       than a browser.</li>
 *   <li>Path as in RFC 6265 (5.1.4; without {@code Path}, the path of the
 *       URI up to the last {@code /}). {@code Secure} cookies go only over
 *       {@code https}, and such a cookie set over {@code http} is not
 *       accepted. A {@code Max-Age} of 0 or less or a past {@code Expires}
 *       deletes the cookie.</li>
 *   <li>Bounded: {@value #MAX_COOKIES_PER_ORIGIN} per origin,
 *       {@value #MAX_COOKIES} in total (the oldest is dropped),
 *       {@value #MAX_COOKIE_CHARS} characters for name and value, and at
 *       most {@value #MAX_HEADER_CHARS} characters for the {@code Cookie}
 *       header.</li>
 *   <li>Sent as {@code name=value}, separated by {@code "; "} (RFC 6265,
 *       5.4); {@code add(null, …)} throws
 *       {@link IllegalArgumentException}.</li>
 * </ul>
 */
final class TckCookieJar implements CookieStore {

    static final int MAX_COOKIES_PER_ORIGIN = 50;
    static final int MAX_COOKIES = 1000;
    static final int MAX_COOKIE_CHARS = 4096;
    static final int MAX_HEADER_CHARS = 8192;

    private record Stored(String origin, HttpCookie cookie, long order) {
    }

    /** Guarded by {@code this}. */
    private final Map<String, List<Stored>> byOrigin = new LinkedHashMap<>();
    private long order;
    private int size;

    /** The {@code Cookie} header for a request to this URI; empty without matching cookies. */
    synchronized Optional<String> header(URI uri) {
        StringBuilder header = new StringBuilder();
        for (HttpCookie cookie : matching(uri)) {
            String pair = cookie.getName() + "=" + (cookie.getValue() == null ? "" : cookie.getValue());
            int separator = header.length() > 0 ? 2 : 0;
            if (header.length() + separator + pair.length() > MAX_HEADER_CHARS) {
                continue;
            }
            if (separator > 0) {
                header.append("; ");
            }
            header.append(pair);
        }
        return header.length() == 0 ? Optional.empty() : Optional.of(header.toString());
    }

    /** Stores the cookies of the {@code Set-Cookie} headers of a response from this URI. */
    synchronized void remember(URI uri, List<String> setCookies) {
        String origin = originOrNull(uri);
        if (origin == null || setCookies.isEmpty()) {
            return;
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        for (String line : setCookies) {
            List<HttpCookie> parsed;
            try {
                parsed = HttpCookie.parse("set-cookie:" + line);
            } catch (IllegalArgumentException unreadable) {
                continue;
            }
            boolean deletes = maxAgeAtMostZero(line);
            for (HttpCookie cookie : parsed) {
                if (deletes) {
                    cookie.setMaxAge(0);
                }
                if (acceptable(cookie) && !(cookie.getSecure() && !https)) {
                    put(origin, uri, cookie);
                }
            }
        }
    }

    @Override
    public synchronized void add(URI uri, HttpCookie cookie) {
        Objects.requireNonNull(cookie, "cookie");
        if (uri == null) {
            throw new IllegalArgumentException("a cookie of this store belongs to the origin of an http or https"
                    + " address");
        }
        String origin = originOrNull(uri);
        if (origin == null) {
            throw new IllegalArgumentException("a cookie of this store belongs to the origin of an http or https"
                    + " address");
        }
        if (!acceptable(cookie)) {
            throw new IllegalArgumentException("cookie <" + cookie.getName() + "> has a value with control"
                    + " characters or ';', or is longer than " + MAX_COOKIE_CHARS + " characters");
        }
        put(origin, uri, cookie);
    }

    @Override
    public synchronized List<HttpCookie> get(URI uri) {
        Objects.requireNonNull(uri, "uri");
        return matching(uri).stream().map(TckCookieJar::copy).toList();
    }

    @Override
    public synchronized List<HttpCookie> getCookies() {
        dropExpired();
        List<HttpCookie> all = new ArrayList<>();
        byOrigin.values().forEach(cookies -> cookies.forEach(stored -> all.add(copy(stored.cookie()))));
        return all;
    }

    @Override
    public synchronized List<URI> getURIs() {
        dropExpired();
        return byOrigin.keySet().stream().map(URI::create).toList();
    }

    @Override
    public synchronized boolean remove(URI uri, HttpCookie cookie) {
        Objects.requireNonNull(cookie, "cookie");
        String only = null;
        if (uri != null) {
            only = originOrNull(uri);
            if (only == null) {
                return false;
            }
        }
        boolean removed = false;
        Iterator<Map.Entry<String, List<Stored>>> origins = byOrigin.entrySet().iterator();
        while (origins.hasNext()) {
            Map.Entry<String, List<Stored>> entry = origins.next();
            if (only != null && !only.equals(entry.getKey())) {
                continue;
            }
            String path = cookie.getPath();
            int before = entry.getValue().size();
            entry.getValue().removeIf(stored -> stored.cookie().getName().equals(cookie.getName())
                    && (path == null || path.equals(stored.cookie().getPath())));
            int gone = before - entry.getValue().size();
            size -= gone;
            removed |= gone > 0;
            if (entry.getValue().isEmpty()) {
                origins.remove();
            }
        }
        return removed;
    }

    @Override
    public synchronized boolean removeAll() {
        boolean any = size > 0;
        byOrigin.clear();
        size = 0;
        return any;
    }

    /** Shows neither names nor values. */
    @Override
    public synchronized String toString() {
        return "TckCookieJar[" + size + " cookies, " + byOrigin.size() + " origins]";
    }

    /** Whether the header has a {@code Max-Age} of 0 or less (RFC 6265, 5.2.2); the last valid one counts. */
    static boolean maxAgeAtMostZero(String setCookie) {
        String[] parts = setCookie.split(";");
        Boolean atMostZero = null;
        for (int i = 1; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals < 0 || !parts[i].substring(0, equals).trim().equalsIgnoreCase("max-age")) {
                continue;
            }
            String value = parts[i].substring(equals + 1).trim();
            String digits = value.startsWith("-") ? value.substring(1) : value;
            if (!digits.isEmpty() && digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                atMostZero = value.startsWith("-") || digits.chars().allMatch(c -> c == '0');
            }
        }
        return Boolean.TRUE.equals(atMostZero);
    }

    /** The origin of a valid request URI; otherwise {@code null}. */
    private static String originOrNull(URI uri) {
        try {
            return TckAdapterHttp.originOf(AdapterRequest.get(uri).uri());
        } catch (IllegalArgumentException notHttp) {
            return null;
        }
    }

    private List<HttpCookie> matching(URI uri) {
        String origin = originOrNull(uri);
        List<Stored> cookies = origin == null ? null : byOrigin.get(origin);
        if (cookies == null) {
            return List.of();
        }
        int before = cookies.size();
        cookies.removeIf(stored -> stored.cookie().hasExpired());
        size -= before - cookies.size();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        List<HttpCookie> matching = new ArrayList<>();
        cookies.stream()
                .filter(stored -> pathMatches(path, stored.cookie().getPath()))
                .filter(stored -> !stored.cookie().getSecure() || https)
                .sorted(Comparator.comparingInt((Stored stored) -> stored.cookie().getPath().length()).reversed()
                        .thenComparingLong(Stored::order))
                .forEach(stored -> matching.add(stored.cookie()));
        if (cookies.isEmpty()) {
            byOrigin.remove(origin);
        }
        return matching;
    }

    private void put(String origin, URI uri, HttpCookie given) {
        HttpCookie cookie = copy(given);
        if (cookie.getPath() == null || !cookie.getPath().startsWith("/")) {
            cookie.setPath(defaultPath(uri));
        }
        List<Stored> cookies = byOrigin.computeIfAbsent(origin, ignored -> new ArrayList<>());
        int before = cookies.size();
        cookies.removeIf(stored -> stored.cookie().getName().equals(cookie.getName())
                && stored.cookie().getPath().equals(cookie.getPath()));
        size -= before - cookies.size();
        if (cookie.hasExpired()) {
            if (cookies.isEmpty()) {
                byOrigin.remove(origin);
            }
            return;
        }
        if (cookies.size() >= MAX_COOKIES_PER_ORIGIN) {
            cookies.stream().min(Comparator.comparingLong(Stored::order)).ifPresent(cookies::remove);
            size--;
        }
        cookies.add(new Stored(origin, cookie, ++order));
        size++;
        if (size > MAX_COOKIES) {
            dropExpired();
        }
        while (size > MAX_COOKIES) {
            dropOldest();
        }
    }

    private void dropExpired() {
        Iterator<List<Stored>> origins = byOrigin.values().iterator();
        while (origins.hasNext()) {
            List<Stored> cookies = origins.next();
            int before = cookies.size();
            cookies.removeIf(stored -> stored.cookie().hasExpired());
            size -= before - cookies.size();
            if (cookies.isEmpty()) {
                origins.remove();
            }
        }
    }

    private void dropOldest() {
        Stored oldest = null;
        for (List<Stored> cookies : byOrigin.values()) {
            for (Stored stored : cookies) {
                if (oldest == null || stored.order() < oldest.order()) {
                    oldest = stored;
                }
            }
        }
        if (oldest == null) {
            size = 0;
            return;
        }
        List<Stored> cookies = byOrigin.get(oldest.origin());
        cookies.remove(oldest);
        size--;
        if (cookies.isEmpty()) {
            byOrigin.remove(oldest.origin());
        }
    }

    private static boolean acceptable(HttpCookie cookie) {
        String value = cookie.getValue() == null ? "" : cookie.getValue();
        if (cookie.getName().length() + value.length() > MAX_COOKIE_CHARS) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || c > 0xff || c == ';') {
                return false;
            }
        }
        return true;
    }

    /** The path of a cookie without {@code Path} (RFC 6265, 5.1.4). */
    static String defaultPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/")) {
            return "/";
        }
        int last = path.lastIndexOf('/');
        return last == 0 ? "/" : path.substring(0, last);
    }

    /** Whether the path of a request matches the path of a cookie (RFC 6265, 5.1.4). */
    static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        return requestPath.startsWith(cookiePath)
                && (cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/');
    }

    private static HttpCookie copy(HttpCookie cookie) {
        return (HttpCookie) cookie.clone();
    }
}
