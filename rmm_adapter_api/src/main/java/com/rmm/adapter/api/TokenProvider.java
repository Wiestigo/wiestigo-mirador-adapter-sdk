package com.rmm.adapter.api;

import java.time.Instant;

/**
 * Hook for obtaining an OAuth 2 token. The adapter knows how to get a token
 * from the tool (for example with client credentials). The host holds the
 * token per binding and origin, sends it as {@code Bearer}, renews it before it
 * expires and, after a 401, at most once per request of the adapter. That
 * request is then sent again only if it is {@link AdapterRequest#repeatable()};
 * otherwise the adapter receives the 401 and the token is discarded.
 *
 * <p><b>HTTP path while fetching:</b> {@link #fetch(AdapterHttp)} receives a
 * view of the binding's HTTP path <b>without</b> the authentications
 * registered through {@link AdapterHttp#auth}, with the same address
 * protection, TLS configuration, rate limit and deadline; its {@code auth(…)}
 * throws {@link UnsupportedOperationException}. The adapter puts the client
 * credentials into the request itself (body or
 * {@link AdapterRequest.Builder#secretHeader}), so fetching never needs a
 * token, even if the token endpoint shares the origin of the registered
 * authentication. Use only this view, never {@link AdapterContext#http()}: a
 * request through that path to the authenticated origin would wait for the
 * very token being fetched.
 *
 * <p>The host never calls the provider concurrently for the same binding. An
 * error while fetching reaches the request that needed the token as
 * {@link AdapterException}.
 *
 * <p><b>Do not log the token response:</b> the host masks the token only after
 * {@link #fetch} has returned it. Anything written to
 * {@link AdapterContext#logger()} before that, such as the response of the
 * token endpoint, appears in the log in plain text.
 */
@FunctionalInterface
public interface TokenProvider {

    /**
     * Fetches a new token from the tool.
     *
     * @param unauthenticated the binding's HTTP path without registered authentication
     */
    Token fetch(AdapterHttp unauthenticated) throws AdapterException;

    /**
     * An access token.
     *
     * @param accessToken the value, sent as {@code Authorization: Bearer …}
     * @param expiresAt   expiry; {@code null} = unknown, the host then renews
     *                    only after a 401 response
     */
    record Token(SecretValue accessToken, Instant expiresAt) {

        public Token {
            if (accessToken == null) {
                throw new IllegalArgumentException("accessToken must not be null");
            }
            if (!HttpRules.isHeaderValue(accessToken.reveal())) {
                throw new IllegalArgumentException("accessToken must not contain control characters");
            }
        }
    }
}
