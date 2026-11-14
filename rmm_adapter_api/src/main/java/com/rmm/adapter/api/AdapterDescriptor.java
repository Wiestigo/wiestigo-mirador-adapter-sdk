package com.rmm.adapter.api;

import java.util.List;

/**
 * Self-description of an adapter: identity, version, capabilities and declared
 * configuration. The host reads and checks it before binding, so it must be
 * constant and independent of configuration.
 *
 * @param systemKey stable identifier of the tool, for example
 *     {@code "polarion"}; letters, digits, {@code -} and {@code _}. It becomes
 *     the system key of the external references on Mirador elements and the
 *     configuration namespace ({@code rmm.adapter.<systemKey>.*}). Once used
 *     in production it must never change; otherwise existing elements lose
 *     their link to the tool.
 * @param displayName display name for administration and logs, for example
 *     {@code "Siemens Polarion"}.
 * @param adapterVersion version of the adapter itself (free-form, SemVer
 *     recommended).
 * @param apiMajorVersion major version of the contract the adapter was built
 *     against ({@link AdapterApiVersion#MAJOR}); the host rejects incompatible
 *     adapters.
 * @param capabilities declared capabilities of the tool (never null).
 * @param configKeys the adapter's configuration keys; the host resolves them
 *     under {@code rmm.adapter.<systemKey>.*} and refuses to bind if a
 *     required key is missing.
 * @param identifierKinds defaults for the identifier kinds by which people or
 *     the tool name artifacts in reference fields; unique names; may be empty.
 *     The tool's mapping file may override each default.
 * @param apiMinorVersion minor version of the contract the adapter was built
 *     against ({@link AdapterApiVersion#MINOR}; since contract 1.1; never
 *     negative). An adapter that uses additions of a minor version states at
 *     least that version; the manifest states the same value
 *     ({@code apiMinor}). The seven-argument constructor sets 0.
 */
public record AdapterDescriptor(String systemKey,
                                String displayName,
                                String adapterVersion,
                                int apiMajorVersion,
                                AdapterCapabilities capabilities,
                                List<ConfigKey> configKeys,
                                List<IdentifierKind> identifierKinds,
                                int apiMinorVersion) {

    /** The descriptor of an adapter built against minor version 0. */
    public AdapterDescriptor(String systemKey,
                             String displayName,
                             String adapterVersion,
                             int apiMajorVersion,
                             AdapterCapabilities capabilities,
                             List<ConfigKey> configKeys,
                             List<IdentifierKind> identifierKinds) {
        this(systemKey, displayName, adapterVersion, apiMajorVersion, capabilities, configKeys, identifierKinds, 0);
    }

    public AdapterDescriptor {
        if (systemKey == null || systemKey.isBlank()) {
            throw new IllegalArgumentException("systemKey must not be empty");
        }
        if (!systemKey.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "systemKey '" + systemKey + "' may only contain letters, digits, '-' and '_'"
                            + " — it becomes the reference key and the configuration namespace");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be empty");
        }
        if (adapterVersion == null || adapterVersion.isBlank()) {
            throw new IllegalArgumentException("adapterVersion must not be empty");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        if (apiMinorVersion < 0) {
            throw new IllegalArgumentException("apiMinorVersion must not be negative: " + apiMinorVersion);
        }
        configKeys = configKeys == null ? List.of() : List.copyOf(configKeys);
        identifierKinds = identifierKinds == null ? List.of() : List.copyOf(identifierKinds);
        java.util.Set<String> kindNames = new java.util.HashSet<>();
        for (IdentifierKind kind : identifierKinds) {
            if (!kindNames.add(kind.name())) {
                throw new IllegalArgumentException("identifier kind '" + kind.name() + "' is declared twice");
            }
        }
    }
}
