package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.RedirectPolicy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Set;

/**
 * Where a redirect leads on the HTTP path: the {@code Location} of a 3xx response, resolved per RFC 3986 as the
 * host does, if the request's {@link RedirectPolicy} follows it.
 */
final class TckHttpRedirect {

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    private TckHttpRedirect() {
    }

    /**
     * The target of a redirect that the policy follows; empty = the response goes to the adapter. {@code Location}
     * is resolved per RFC 3986, without fragment; a target that is not a valid request address is not followed,
     * as in the host.
     */
    static Optional<URI> target(AdapterRequest request, TckHttpAnswer answer, URI from, int hop) {
        if (!REDIRECTS.contains(answer.status())) {
            return Optional.empty();
        }
        Optional<String> location = answer.firstHeader("Location");
        if (location.isEmpty()) {
            return Optional.empty();
        }
        URI to;
        try {
            to = resolve(from, new URI(location.get().trim()));
            if (RedirectPolicy.refusal(request, answer.status(), from, to, hop).isPresent()) {
                return Optional.empty();
            }
            AdapterRequest.get(to);
        } catch (URISyntaxException | IllegalArgumentException unusable) {
            return Optional.empty();
        }
        return Optional.of(to);
    }

    /**
     * Resolves a reference per RFC 3986, 5.2.2 (with 5.2.3 and 5.2.4), without fragment, as the host does. Not
     * {@link URI#resolve}, which follows RFC 2396 ({@code ?q=1} against {@code /a/b} would give {@code /a/?q=1}).
     */
    static URI resolve(URI base, URI reference) throws URISyntaxException {
        if (reference.getScheme() != null) {
            if (reference.isOpaque()) {
                return new URI(reference.getScheme() + ":" + reference.getRawSchemeSpecificPart());
            }
            return compose(reference.getScheme(), reference.getRawAuthority(),
                    removeDotSegments(raw(reference.getRawPath())), reference.getRawQuery());
        }
        if (reference.getRawAuthority() != null) {
            return compose(base.getScheme(), reference.getRawAuthority(),
                    removeDotSegments(raw(reference.getRawPath())), reference.getRawQuery());
        }
        String path = raw(reference.getRawPath());
        if (path.isEmpty()) {
            return compose(base.getScheme(), base.getRawAuthority(), raw(base.getRawPath()),
                    reference.getRawQuery() != null ? reference.getRawQuery() : base.getRawQuery());
        }
        if (!path.startsWith("/")) {
            String basePath = raw(base.getRawPath());
            path = base.getRawAuthority() != null && basePath.isEmpty() ? "/" + path
                    : basePath.substring(0, basePath.lastIndexOf('/') + 1) + path;
        }
        return compose(base.getScheme(), base.getRawAuthority(), removeDotSegments(path), reference.getRawQuery());
    }

    /** RFC 3986, 5.2.4: removes {@code .} and {@code ..} from a path. */
    static String removeDotSegments(String path) {
        StringBuilder output = new StringBuilder();
        String input = path;
        while (!input.isEmpty()) {
            if (input.startsWith("../")) {
                input = input.substring(3);
            } else if (input.startsWith("./")) {
                input = input.substring(2);
            } else if (input.startsWith("/./")) {
                input = input.substring(2);
            } else if (input.equals("/.")) {
                input = "/";
            } else if (input.startsWith("/../")) {
                input = input.substring(3);
                output.setLength(Math.max(0, output.lastIndexOf("/")));
            } else if (input.equals("/..")) {
                input = "/";
                output.setLength(Math.max(0, output.lastIndexOf("/")));
            } else if (input.equals(".") || input.equals("..")) {
                input = "";
            } else {
                int end = input.indexOf('/', input.startsWith("/") ? 1 : 0);
                end = end < 0 ? input.length() : end;
                output.append(input, 0, end);
                input = input.substring(end);
            }
        }
        return output.toString();
    }

    private static String raw(String part) {
        return part == null ? "" : part;
    }

    private static URI compose(String scheme, String authority, String path, String query) throws URISyntaxException {
        StringBuilder text = new StringBuilder(scheme).append(':');
        if (authority != null) {
            text.append("//").append(authority);
        }
        text.append(path);
        if (query != null) {
            text.append('?').append(query);
        }
        return new URI(text.toString());
    }
}
