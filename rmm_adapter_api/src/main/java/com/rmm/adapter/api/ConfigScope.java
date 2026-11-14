package com.rmm.adapter.api;

/**
 * Scope of a declared configuration key: where the host takes the value from
 * and who sees it. BINDING values come only from the tenant's configuration,
 * PLATFORM values only from the deployment.
 */
public enum ConfigScope {

    /**
     * Value of this connection (the default): maintained by the tenant's
     * administrator, for example address, account, token, project filter.
     */
    BINDING,

    /**
     * Value of the deployment: set by the operator (environment, file) and
     * invisible to the tenant, for example a proxy or upper limits. Only here
     * is {@link ConfigType#PATH} allowed; the host and the TCK reject it in
     * scope BINDING.
     */
    PLATFORM
}
