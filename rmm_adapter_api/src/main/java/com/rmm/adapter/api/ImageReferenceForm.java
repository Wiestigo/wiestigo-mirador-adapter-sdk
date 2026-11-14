package com.rmm.adapter.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A form in which a tool refers to an image in its rich text and which the sync
 * core keeps when saving (since contract 1.1). If the core cannot fetch an
 * embedded image into its image store (resolution switched off, loading
 * failed, too large), the tool's reference stays, so that writing back does
 * not take the image away from the tool.
 *
 * <p><b>A closed grammar of the core, no regular expression from the
 * plugin.</b> A form is exactly one of:
 * <ul>
 *   <li>{@link #scheme(String)}: an opaque scheme {@code <name>:} followed by
 *       an image file name that passes the core's name filter
 *       ({@link #FILE_NAME}). Modifiers after {@code |} are dropped when
 *       saving;</li>
 *   <li>{@link #pathTemplate(String)}: a relative path template of fixed
 *       segments and the placeholders {@code {number}} (digits),
 *       {@code {file}} (such a file name; always and only the last segment)
 *       and at most one {@code {ctx}} (no segment or one, such as a context
 *       path).</li>
 * </ul>
 *
 * <p><b>Prohibited in every form</b>, both in a declaration and in a checked
 * value: the schemes in {@link #BANNED_SCHEMES}; {@code //}, {@code ..} and
 * {@code %} in path forms; {@code /api/} and the routes of the host
 * ({@link #RMM_ROUTES}) as a segment. A template needs at least one fixed
 * segment. A form outside the grammar cannot be constructed
 * ({@link IllegalArgumentException}), so an adapter that declares one fails at
 * registration.
 *
 * <p>The core decides per source which forms apply to a value: those of the
 * adapter types to whose enabled connections the artifact carries a reference,
 * taken from the manifest of the plugin version released for the tenant. A
 * type that is not released for the tenant contributes no form.
 *
 * @param kind  kind of the form
 * @param value name of the scheme (in lower case) or the template
 */
public record ImageReferenceForm(Kind kind, String value) {

    /** The kind of a form. */
    public enum Kind {
        /** {@code <name>:<file>}. */
        SCHEME,
        /** A relative path template, for example {@code /{ctx}/attachment/{number}/{file}}. */
        PATH_TEMPLATE
    }

    /** Schemes no form may use (case-insensitive). */
    public static final Set<String> BANNED_SCHEMES = Set.of(
            "http", "https", "data", "javascript", "vbscript", "file", "blob");

    /**
     * The paths under which a service answers on the host's origin (proxy
     * routes), plus {@code api}. No segment of a path form and no value for
     * {@code {ctx}} may have one of these names (case-insensitive).
     */
    public static final Set<String> RMM_ROUTES = Set.of(
            "api", "actuator", "internal", "ops", "intelligence", "document", "identity", "_auth", "_health");

    /** Placeholder of a path template: digits. */
    public static final String NUMBER = "{number}";
    /** Placeholder of a path template: the file name of an image, only as the last segment. */
    public static final String FILE = "{file}";
    /** Placeholder of a path template: no segment or one segment, at most once. */
    public static final String CTX = "{ctx}";

    /**
     * The core's name filter for an image file name: no path, no whitespace, no
     * control, format or separator characters ({@code \p{Cc}}, {@code \p{Cf}},
     * {@code \p{Z}}; Java's {@code \s} covers only ASCII whitespace), none of
     * the characters with which the outbound direction could smuggle markup
     * into the tool ({@code | ! [ ] { }}), no {@code %}, with an image
     * extension, at most 255 characters.
     */
    public static final Pattern FILE_NAME = Pattern.compile(
            "^[^\\s\\p{Cc}\\p{Cf}\\p{Z}|!\\[\\]{}/\\\\:\"'<>%]{1,250}\\.(?:png|jpe?g|gif|webp|bmp|svg)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHEME_NAME = Pattern.compile("^[a-z][a-z0-9+.-]{0,31}$");
    /** One path segment that does not start with a dot: a fixed segment of a template or a value for {@code {ctx}}. */
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_~-][A-Za-z0-9._~-]{0,63}$");
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,18}$");
    private static final int MAX_REFERENCE_LENGTH = 512;

    public ImageReferenceForm {
        Objects.requireNonNull(kind, "kind");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An image reference form needs a value");
        }
        value = kind == Kind.SCHEME ? value.trim().toLowerCase(Locale.ROOT) : value.trim();
        List<String> problems = problemsOf(kind, value);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Image reference form <" + value + "> is outside the grammar of the"
                    + " core: " + String.join("; ", problems));
        }
    }

    /** The form {@code <name>:<file>}. */
    public static ImageReferenceForm scheme(String name) {
        return new ImageReferenceForm(Kind.SCHEME, name);
    }

    /** A relative path template, for example {@code /{ctx}/attachment/{number}/{file}}. */
    public static ImageReferenceForm pathTemplate(String template) {
        return new ImageReferenceForm(Kind.PATH_TEMPLATE, template);
    }

    /**
     * The violations of a declaration against the grammar and the fixed
     * prohibitions; empty if it is valid. For checks that do not want to
     * construct a form (such as the TCK).
     */
    public static List<String> problemsOf(Kind kind, String value) {
        List<String> problems = new ArrayList<>();
        if (kind == null || value == null || value.isBlank()) {
            problems.add("kind and value are required");
            return problems;
        }
        if (kind == Kind.SCHEME) {
            String name = value.trim().toLowerCase(Locale.ROOT);
            if (!SCHEME_NAME.matcher(name).matches()) {
                problems.add("a scheme is a lower-case name of letters, digits, '+', '.' and '-', starting with"
                        + " a letter, without ':'");
            }
            if (BANNED_SCHEMES.contains(name)) {
                problems.add("the scheme <" + name + "> is banned");
            }
            return problems;
        }
        String template = value.trim();
        if (!template.startsWith("/")) {
            problems.add("a path template starts with '/'");
            return problems;
        }
        if (template.contains("//") || template.contains("..") || template.contains("%")
                || template.contains("\\") || template.endsWith("/")) {
            problems.add("'//', '..', '%', '\\' and a trailing '/' are banned in path forms");
            return problems;
        }
        String[] segments = template.substring(1).split("/", -1);
        int contexts = 0;
        int fixed = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            switch (segment) {
                case CTX -> contexts++;
                case NUMBER -> {
                }
                case FILE -> {
                    if (i != segments.length - 1) {
                        problems.add("{file} may only be the last segment");
                    }
                }
                default -> {
                    if (!SEGMENT.matcher(segment).matches()) {
                        problems.add("the segment <" + segment + "> is neither a placeholder ({number}, {file},"
                                + " {ctx}) nor a fixed segment of letters, digits, '.', '_', '~' and '-'");
                    } else if (RMM_ROUTES.contains(segment.toLowerCase(Locale.ROOT))) {
                        problems.add("the segment <" + segment + "> is a route of Mirador");
                    } else {
                        fixed++;
                    }
                }
            }
        }
        if (contexts > 1) {
            problems.add("at most one {ctx}");
        }
        if (fixed == 0) {
            problems.add("a path template needs at least one fixed segment");
        }
        if (!FILE.equals(segments[segments.length - 1])) {
            // Otherwise something like <img src="/logout"> would be kept: a route on the host's origin
            problems.add("the last segment of a path template is {file} (the file name of an image)");
        }
        return problems;
    }

    /**
     * The value in the form in which the core stores it, if it matches this
     * form; otherwise empty. A scheme reference loses its modifiers after
     * {@code |} and carries the scheme in lower case; a path stays as it is.
     */
    public Optional<String> admit(String reference) {
        if (reference == null || reference.isEmpty() || reference.length() > MAX_REFERENCE_LENGTH) {
            return Optional.empty();
        }
        return kind == Kind.SCHEME ? admitScheme(reference) : admitPath(reference);
    }

    /** A reference of this form, for the TCK case and for tests. */
    public String example() {
        if (kind == Kind.SCHEME) {
            return value + ":example.png";
        }
        StringBuilder example = new StringBuilder();
        for (String segment : value.substring(1).split("/", -1)) {
            switch (segment) {
                case CTX -> {
                }
                case NUMBER -> example.append("/1");
                case FILE -> example.append("/example.png");
                default -> example.append('/').append(segment);
            }
        }
        return example.toString();
    }

    private Optional<String> admitScheme(String reference) {
        String prefix = value + ":";
        if (!reference.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return Optional.empty();
        }
        String name = reference.substring(prefix.length());
        int modifiers = name.indexOf('|');
        if (modifiers >= 0) {
            name = name.substring(0, modifiers);
        }
        return FILE_NAME.matcher(name).matches() ? Optional.of(prefix + name) : Optional.empty();
    }

    private Optional<String> admitPath(String reference) {
        if (!reference.startsWith("/") || reference.contains("//") || reference.contains("..")
                || reference.contains("%") || reference.contains("\\")
                || reference.toLowerCase(Locale.ROOT).contains("/api/")) {
            return Optional.empty();
        }
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c <= ' ' || c == '?' || c == '#' || c == '"' || c == '\'' || c == '<' || c == '>' || c > '~') {
                return Optional.empty();
            }
        }
        String[] template = value.substring(1).split("/", -1);
        String[] actual = reference.substring(1).split("/", -1);
        boolean hasContext = java.util.Arrays.asList(template).contains(CTX);
        boolean contextFilled;
        if (actual.length == template.length) {
            contextFilled = hasContext;
        } else if (hasContext && actual.length == template.length - 1) {
            contextFilled = false;
        } else {
            return Optional.empty();
        }
        int position = 0;
        for (String segment : template) {
            if (CTX.equals(segment)) {
                if (!contextFilled) {
                    continue;
                }
                String context = actual[position++];
                if (!SEGMENT.matcher(context).matches()
                        || RMM_ROUTES.contains(context.toLowerCase(Locale.ROOT))) {
                    return Optional.empty();
                }
                continue;
            }
            String part = actual[position++];
            boolean matches = switch (segment) {
                case NUMBER -> DIGITS.matcher(part).matches();
                case FILE -> FILE_NAME.matcher(part).matches() && !part.startsWith(".");
                default -> segment.equals(part);
            };
            if (!matches) {
                return Optional.empty();
            }
        }
        return Optional.of(reference);
    }
}
