package com.rmm.adapter.api;

import java.net.URI;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Limits and facts of the host that an adapter must know but does not configure
 * itself: the maximum attachment size and the public address of Mirador (for
 * example for callback addresses registered at the tool). Obtained through
 * {@link AdapterConfig#host()}.
 *
 * @param maxAttachmentBytes maximum size of an attachment in bytes (greater
 *                           than 0); empty = not known to the adapter; the
 *                           core checks the limit anyway
 * @param publicUrl          public address of Mirador (absolute, http or
 *                           https); empty = not configured
 */
public record HostLimits(OptionalLong maxAttachmentBytes, Optional<URI> publicUrl) {

    private static final HostLimits UNKNOWN = new HostLimits(OptionalLong.empty(), Optional.empty());

    public HostLimits {
        if (maxAttachmentBytes == null || publicUrl == null) {
            throw new IllegalArgumentException("host limits must not be null — use an empty optional");
        }
        if (maxAttachmentBytes.isPresent() && maxAttachmentBytes.getAsLong() <= 0) {
            throw new IllegalArgumentException("maxAttachmentBytes must be positive: " + maxAttachmentBytes.getAsLong());
        }
        publicUrl.ifPresent(url -> HttpRules.requireHttpUri(url, "publicUrl"));
    }

    /** Nothing known: the default of a context that does not provide these facts. */
    public static HostLimits unknown() {
        return UNKNOWN;
    }
}
