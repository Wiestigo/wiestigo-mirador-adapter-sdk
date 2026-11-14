package com.rmm.adapter.api;

import java.util.Locale;

/**
 * Conventions agreed between adapters and the host: names both sides must know
 * without configuring them.
 */
public final class AdapterConventions {

    /**
     * Field name of the comment text on an {@code ElementComment} delivered by
     * a {@link SourceReader}. Adapters deliver the text under exactly this name
     * unless they declare other names ({@link MetadataProvider#commentFields()}).
     */
    public static final String COMMENT_BODY_FIELD = "Body";

    /**
     * Field name of the thread predecessor on an {@code ElementComment}: the
     * external ID of the comment being replied to. Tools without comment
     * threads omit the field.
     */
    public static final String COMMENT_IN_REPLY_TO_FIELD = "InReplyTo";

    /**
     * Field name of the comment author on an {@code ElementComment}, delivered
     * as {@code ElementFieldStringUser} (user name and display name) so that
     * the host can map the author to an account. Without the field, the run
     * writes the comment under its own actor.
     */
    public static final String COMMENT_AUTHOR_FIELD = "Author";

    /**
     * Field name of the derived child type on an outgoing
     * {@code ElementArtifact}: the tool-mapped type of the structural
     * descendants of a container artifact (the first descendant type that is
     * neither its own type nor a container or text type). The host sets the
     * field only if a type can be derived; tools without a child type ignore
     * it. A tool such as Jama needs it for the {@code childItemType} of its
     * sets and folders.
     */
    public static final String CHILD_TYPE_FIELD = "Child Type";

    /**
     * Reserved configuration key: the rate limit of one connection on the
     * host's HTTP path ({@link AdapterHttp}), in attempts per second.
     *
     * <p>The host reads it only if the adapter declares it in
     * {@link AdapterDescriptor#configKeys()} as {@link ConfigType#INTEGER} in
     * scope {@link ConfigScope#BINDING}, with no default or a default of 0 or
     * more; declared otherwise, the adapter type is defective at load time and
     * fails the TCK. The platform cap is the declared PLATFORM key
     * {@code http.rate.per.second.max} ({@link AdapterConfig#limit}). The
     * effective rate is {@code min(connection value, RateLimit of the
     * descriptor, platform cap, cap per target host)}; without a value and
     * without a default, the {@link RateLimit} applies. Read at bind time; a
     * change rebinds.
     *
     * <p><b>0 is read as 1,</b> the slowest rate, never "no limit": otherwise
     * a tenant could lift the {@link RateLimit}. Each host instance gets its
     * share of the rate but at least one request per second, so with
     * {@code n} instances the tool may see up to {@code n} requests per
     * second. The administration does not store a negative value; binding
     * with one fails.
     */
    public static final String HTTP_RATE_PER_SECOND_KEY = "http.rate.per.second";

    /**
     * Reserved configuration key: the maximum number of retries of a
     * repeatable request on the host's HTTP path
     * ({@link AdapterRequest#repeatable()}; after 429, 503 and transient
     * transport errors).
     *
     * <p>Declared and read like {@link #HTTP_RATE_PER_SECOND_KEY}
     * ({@code INTEGER}, {@code BINDING}, platform cap {@code http.retries.max}).
     * The effective value is {@code min(connection value,
     * RateLimit#maxRetries(), three)}; 0 means one attempt and no retry.
     * Without a value, the {@link RateLimit} applies. The administration does
     * not store a negative value. The TCK honors the key with the same number
     * of attempts as the host.
     */
    public static final String HTTP_RETRIES_KEY = "http.retries";

    /**
     * Conventional configuration key: the TCP connect timeout of one connection
     * on the host's HTTP path ({@link AdapterHttp}), in seconds. Since contract
     * 1.1.
     *
     * <p>The host reads it only if the adapter declares it as
     * {@link ConfigType#INTEGER} in {@link AdapterDescriptor#configKeys()} or,
     * if the type does not declare it, the binding declares it in
     * {@link AdapterInstance#connectionKeys()}. The effective timeout is then
     * {@code max(1, connection value)}, capped at the connect timeout the
     * operator configured for the host. Without a value, the default of the key
     * applies; without both, or with a value that is not an integer, the
     * operator's timeout applies. Declared otherwise (for example as
     * {@code STRING}), the host does not read it for the HTTP path. The value
     * is read when the HTTP path is built; a change rebinds.
     */
    public static final String CONNECT_TIMEOUT_KEY = "timeout.connect";

    /**
     * Name of the mapping file of an adapter type:
     * {@code interface_<systemKey>_mapping.json}, with the key in lower case.
     * The host uses it as the file name of a mapping in its administration;
     * a plugin delivers its template under the fixed resource name of the
     * plugin unit instead.
     */
    public static String mappingFileNameFor(String systemKey) {
        return "interface_" + systemKey.toLowerCase(Locale.ROOT) + "_mapping.json";
    }

    private AdapterConventions() {
    }
}
