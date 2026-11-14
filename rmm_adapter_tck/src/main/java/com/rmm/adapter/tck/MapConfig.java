package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterConfig;
import com.rmm.adapter.api.AdapterConventions;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigScope;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HostLimits;
import com.rmm.adapter.api.SecretValue;

import java.net.URI;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The configuration of a binding in the TCK, typed against the declaration.
 * It applies the host's rules, so a configuration that passes here can also
 * exist in the host:
 * <ul>
 *   <li><b>Two sources:</b> the connection's values (set by the tenant) and
 *       the deployment's (set by the operator). A {@link ConfigScope#PLATFORM}
 *       key comes only from the deployment or its default; a
 *       {@link ConfigScope#BINDING} key from the connection, else the
 *       deployment, else its default. A secret of a connection
 *       ({@link #isSecret}) is never read from the deployment, as in a
 *       multi-tenant host; such a value there fails the build.</li>
 *   <li><b>Rejected at build time, as the host rejects on save</b>
 *       ({@link MapConfigChecks}): in the
 *       connection's values a PLATFORM key or a cap {@code <name>.max}, a
 *       value longer than {@value #MAX_VALUE_LENGTH} characters (a
 *       certificate: {@value TckPem#MAX_CHARS}), an integer that is no
 *       number or exceeds its cap, a URL that {@link #url} would not accept,
 *       a certificate that is no usable PEM.</li>
 *   <li><b>Typed accessors</b> require the declared type: {@link #secret}
 *       {@code SECRET}, {@link #url} {@code URL}, {@link #certificate}
 *       {@code CERTIFICATE}, {@link #limit} {@code INTEGER}.</li>
 *   <li><b>Caps:</b> {@link #limit} returns {@code min(value, k.max)}. The
 *       cap {@code k.max} is a PLATFORM/INTEGER key, read from the
 *       deployment, else its default. A cap that is declared otherwise, has
 *       a default that is neither empty nor an integer of at least 0, or
 *       caps a key that is not INTEGER fails the build (in the host, loading
 *       the adapter type fails).</li>
 *   <li><b>Reserved keys of the HTTP path</b>
 *       ({@link AdapterConventions#HTTP_RATE_PER_SECOND_KEY},
 *       {@link AdapterConventions#HTTP_RETRIES_KEY}) must be declared as
 *       {@code INTEGER} in scope {@code BINDING} with an empty default or an
 *       integer of at least 0. Both are read at build time
 *       ({@link #reservedLimit}): a value the host would not bind with
 *       (negative, no number, above its cap) fails the build, also for
 *       {@code http.rate.per.second}, although the TCK does not throttle.</li>
 *   <li><b>Client identity</b> ({@link ConfigType#CLIENT_IDENTITY}):
 *       {@link #string} and {@link #certificate} return only the public
 *       certificate of the connection's identity (an empty text or no
 *       certificate without one); only the HTTP path gets the private key
 *       ({@link #clientIdentity()}). Such a key is never missing, takes no
 *       value from either source, and must be declared in scope
 *       {@code BINDING} without a default.</li>
 *   <li><b>Keys of the binding:</b> after {@link #declareConnectionKeys},
 *       {@code config()} also reads the keys the instance declares beyond
 *       its type ({@code AdapterInstance#connectionKeys()}), resolved like
 *       BINDING keys. Before the call they are undeclared, as in the host
 *       before the first bind.</li>
 *   <li><b>Secrets:</b> {@link #revealed()} remembers the secrets handed
 *       out so that the TCK's log masks them as the host does;
 *       {@link #toString()} shows no value.</li>
 * </ul>
 */
final class MapConfig implements AdapterConfig {

    /** Suffix of the key that holds the PLATFORM cap of a limit. */
    static final String CAP_SUFFIX = ".max";
    /** The log does not mask shorter secrets, as in the host. */
    static final int MIN_MASKED_LENGTH = 4;
    /** The host's settings store saves no longer value. */
    static final int MAX_VALUE_LENGTH = 4000;
    /** The reserved keys of the HTTP path, which the host reads if the adapter declares them. */
    static final List<String> RESERVED_KEYS = List.of(AdapterConventions.HTTP_RATE_PER_SECOND_KEY,
            AdapterConventions.HTTP_RETRIES_KEY);
    /** The host's naming rule for secrets: the key ends with one of these words. */
    static final java.util.regex.Pattern SECRET_SUFFIX = java.util.regex.Pattern.compile(
            "(?i)(password|passwd|secret|token|credentials?|apikey|api.key)$");

    /** The keys of the adapter type ({@code AdapterDescriptor#configKeys()}). */
    private final Map<String, ConfigKey> typeKeys = new LinkedHashMap<>();
    /**
     * The declared keys: those of the type plus those of the binding
     * ({@link #declareConnectionKeys}). Never mutated, only replaced, so an
     * adapter thread always reads a consistent snapshot.
     */
    private volatile Map<String, ConfigKey> declared;
    private final Map<String, String> binding;
    private final Map<String, String> deployment;
    private final HostLimits hostLimits;
    private final Set<String> revealed = ConcurrentHashMap.newKeySet();
    private final Map<String, TckPem.Material> parsedPem = new ConcurrentHashMap<>();
    /** The connection's client identity (certificate and key); empty if there is none. */
    private final Optional<TckPem.Material> identity;
    /** Its public certificate as PEM, as the host shows it to the adapter; empty without an identity. */
    private final String identityPem;

    MapConfig(AdapterDescriptor descriptor, Map<String, String> binding, Map<String, String> deployment,
              HostLimits hostLimits) {
        this(descriptor, binding, deployment, hostLimits, Optional.empty());
    }

    /**
     * @param identity the connection's client identity, certificate and key; empty if there is none
     * @throws IllegalArgumentException also if there is an identity but the adapter declares no
     *                                  {@code CLIENT_IDENTITY} key (the host creates none then)
     */
    MapConfig(AdapterDescriptor descriptor, Map<String, String> binding, Map<String, String> deployment,
              HostLimits hostLimits, Optional<TckPem.Material> identity) {
        for (ConfigKey key : descriptor.configKeys()) {
            typeKeys.put(key.key(), key);
        }
        this.declared = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(typeKeys));
        this.binding = new LinkedHashMap<>(binding);
        this.deployment = new LinkedHashMap<>(deployment);
        this.hostLimits = hostLimits;
        this.identity = identity;
        this.identityPem = identity.map(material -> TckPem.certificatesPem(material.certificates())).orElse("");
        if (identity.isPresent() && !declaresClientIdentity()) {
            throw new IllegalArgumentException("The adapter declares no CLIENT_IDENTITY key: the host creates a"
                    + " client identity only for a type that declares one");
        }
        MapConfigChecks.refuseWhatTheHostWouldNotLoad(declared);
        MapConfigChecks.refuseWhatTheHostWouldNotSave(declared, this.binding, this::capOf);
        MapConfigChecks.refuseSecretsOfTheConnectionInTheDeployment(declared, this.deployment);
        refuseReservedValuesTheHostWouldNotBind();
    }

    /**
     * Declares the additional keys of the binding. Each call replaces the
     * previous ones; a name the type already declares stays the type's. As in
     * the host, only what a binding may declare is taken
     * ({@link TckBindingDeclaration#ALLOWED_TYPES} in scope {@code BINDING});
     * other entries are skipped here and reported by the case
     * {@code theBindingDeclarationOnlyNarrowsItsType}. Empty or {@code null}:
     * only the type's keys.
     */
    synchronized void declareConnectionKeys(List<ConfigKey> keys) {
        Map<String, ConfigKey> next = new LinkedHashMap<>(typeKeys);
        if (keys != null) {
            for (ConfigKey key : keys) {
                if (key != null && TckBindingDeclaration.ALLOWED_TYPES.contains(key.type())
                        && key.scope() == ConfigScope.BINDING) {
                    next.putIfAbsent(key.key(), key);
                }
            }
        }
        declared = java.util.Collections.unmodifiableMap(next);
    }

    /**
     * The value of a reserved key as the host reads it when binding: empty if
     * the adapter does not declare the key or the key has neither value nor
     * default; else {@link #limit}.
     *
     * @throws IllegalArgumentException if the value is not an integer of at least 0
     */
    OptionalInt reservedLimit(String key) {
        if (!RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("Configuration key <" + key + "> is not reserved for the network path");
        }
        if (!declared.containsKey(key)) {
            return OptionalInt.empty();
        }
        String value = resolved(key);
        return value == null || value.isBlank() ? OptionalInt.empty() : OptionalInt.of(limit(key));
    }

    /**
     * Reads the reserved keys as the host does when binding
     * ({@link #reservedLimit}), so that a value the host would not bind with
     * fails the build. This includes {@code http.rate.per.second}, which the
     * TCK does not read otherwise.
     */
    private void refuseReservedValuesTheHostWouldNotBind() {
        for (String key : RESERVED_KEYS) {
            reservedLimit(key);
        }
    }

    /**
     * Whether the host treats the key as a secret: declared as {@code SECRET}
     * or {@code CERTIFICATE}, or matching the naming rule.
     */
    static boolean isSecret(ConfigKey key) {
        return key.type() == ConfigType.SECRET || key.type() == ConfigType.CERTIFICATE
                || SECRET_SUFFIX.matcher(key.key().trim()).find();
    }

    /**
     * Declared required keys without a value in their source; empty if the
     * connection can be bound. As in the host, a client identity is never
     * missing: it has no value that could be set.
     */
    List<String> missingRequiredKeys() {
        List<String> missing = new ArrayList<>();
        for (ConfigKey key : declared.values()) {
            if (key.type() == ConfigType.CLIENT_IDENTITY) {
                continue;
            }
            if (key.isRequired() && rawValue(key) == null) {
                missing.add(key.key());
            }
        }
        return missing;
    }

    @Override
    public String string(String key) {
        ConfigKey declaration = declaration(key);
        String value = resolved(declaration);
        if (declaration.type() == ConfigType.SECRET) {
            reveal(value);
        }
        return value;
    }

    @Override
    public int integer(String key) {
        String value = resolved(key);
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("Configuration value <" + key + "> = <" + value
                    + "> is not an integer", e);
        }
    }

    @Override
    public boolean bool(String key) {
        return Boolean.parseBoolean(present(key).trim());
    }

    @Override
    public Path path(String key) {
        return Path.of(present(key));
    }

    @Override
    public SecretValue secret(String key) {
        String value = resolved(requireType(key, ConfigType.SECRET));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Configuration value <" + key + "> is empty — a secret needs a value");
        }
        reveal(value);
        return SecretValue.of(value);
    }

    @Override
    public URI url(String key) {
        requireType(key, ConfigType.URL);
        //The contract's rules (absolute, http/https, host, no credentials, Punycode); the message never shows the value
        return AdapterConfig.super.url(key);
    }

    @Override
    public int limit(String key) {
        ConfigKey declaration = requireType(key, ConfigType.INTEGER);
        int value = AdapterConfig.super.limit(key);
        if (declaration.scope() == ConfigScope.PLATFORM) {
            return value;
        }
        Integer cap = capOf(key);
        return cap == null ? value : Math.min(value, cap);
    }

    @Override
    public List<X509Certificate> certificate(String key) {
        ConfigKey declaration = declared.get(key);
        if (declaration != null && declaration.type() == ConfigType.CLIENT_IDENTITY) {
            //As in the host: only the identity's public certificate, none without an identity
            return identity.map(TckPem.Material::certificates).orElse(List.of());
        }
        String value = resolved(requireType(key, ConfigType.CERTIFICATE));
        return parsed(key, value == null ? "" : value).certificates();
    }

    @Override
    public HostLimits host() {
        return hostLimits;
    }

    /** Whether the adapter declares a {@code CLIENT_IDENTITY} key; the HTTP path then requires an identity. */
    boolean declaresClientIdentity() {
        return declared.values().stream().anyMatch(key -> key.type() == ConfigType.CLIENT_IDENTITY);
    }

    /** The connection's client identity including its key; for the HTTP path only, never for the adapter. */
    Optional<TckPem.Material> clientIdentity() {
        return identity;
    }

    /**
     * The connection's TLS material, as in the host: every declared
     * {@code CERTIFICATE} key that has a value, including a private key it
     * contains (which stays inside the HTTP path).
     */
    List<TckPem.Material> certificateMaterials() {
        List<TckPem.Material> materials = new ArrayList<>();
        for (ConfigKey key : declared.values()) {
            if (key.type() == ConfigType.CERTIFICATE) {
                String value = resolved(key.key());
                if (value != null && !value.isBlank()) {
                    materials.add(parsed(key.key(), value));
                }
            }
        }
        return materials;
    }

    /** The secrets this binding has handed out; the TCK's log masks them. */
    Set<String> revealed() {
        return Set.copyOf(revealed);
    }

    /** The text with every revealed secret masked (longest first), as the host masks. */
    String mask(String text) {
        if (text == null || revealed.isEmpty()) {
            return text;
        }
        List<String> longestFirst = new ArrayList<>(revealed);
        longestFirst.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String masked = text;
        for (String value : longestFirst) {
            masked = masked.replace(value, "****");
        }
        return masked;
    }

    /** Shows no values: neither secrets nor URLs belong in output. */
    @Override
    public String toString() {
        return "MapConfig[" + declared.size() + " declared keys, " + binding.size() + " connection values, "
                + deployment.size() + " deployment values]";
    }

    private void reveal(String value) {
        if (value != null && value.length() >= MIN_MASKED_LENGTH) {
            revealed.add(value);
        }
    }

    /** The PEM material of a value of {@code key}; each value is parsed once. */
    private TckPem.Material parsed(String key, String pem) {
        return parsedPem.computeIfAbsent(pem, value -> TckPem.parse(value, "Configuration value <" + key + ">"));
    }

    private ConfigKey declaration(String key) {
        ConfigKey declaration = declared.get(key);
        if (declaration == null) {
            throw new IllegalArgumentException("Configuration key <" + key + "> is not declared by this adapter"
                    + " — declare it in AdapterDescriptor.configKeys()");
        }
        return declaration;
    }

    private ConfigKey requireType(String key, ConfigType type) {
        ConfigKey declaration = declaration(key);
        if (declaration.type() != type) {
            throw new IllegalArgumentException("Configuration key <" + key + "> is declared as " + declaration.type()
                    + ", not as " + type + " — declare it with ConfigType." + type);
        }
        return declaration;
    }

    private String resolved(String key) {
        return resolved(declaration(key));
    }

    /** The value set in the key's source, else its default; {@code null} for a required key without a value. */
    private String resolved(ConfigKey declaration) {
        String value = rawValue(declaration);
        return value == null ? declaration.defaultValue() : value;
    }

    /**
     * The value of a key that has one. A required key that the binding declares after the build
     * ({@link #declareConnectionKeys}) can be without a value.
     *
     * @throws IllegalArgumentException if the key has neither a value nor a default
     */
    private String present(String key) {
        String value = resolved(key);
        if (value == null) {
            throw new IllegalArgumentException("Configuration value <" + key + "> is missing — a required key"
                    + " needs a value");
        }
        return value;
    }

    /**
     * The value set in the key's source (see the class comment);
     * {@code null} without a value. A client identity has no source: the
     * result is its public certificate, empty without an identity as in the
     * host, and never the default.
     */
    private String rawValue(ConfigKey key) {
        if (key.type() == ConfigType.CLIENT_IDENTITY) {
            return identityPem;
        }
        String fromDeployment = nonBlank(deployment.get(key.key()));
        if (key.scope() == ConfigScope.PLATFORM) {
            return fromDeployment;
        }
        String fromBinding = nonBlank(binding.get(key.key()));
        if (fromBinding != null || isSecret(key)) {
            //A secret of the connection never comes from the operator's file (multi-tenant mode)
            return fromBinding;
        }
        return fromDeployment;
    }

    /**
     * The PLATFORM cap {@code key.max}, if declared: from the deployment,
     * else its default; {@code null} without a cap.
     */
    private Integer capOf(String key) {
        ConfigKey cap = declared.get(key + CAP_SUFFIX);
        if (cap == null) {
            return null;
        }
        if (cap.scope() != ConfigScope.PLATFORM || cap.type() != ConfigType.INTEGER) {
            throw new IllegalArgumentException("Configuration key <" + cap.key() + "> caps <" + key
                    + "> and must be declared as INTEGER in scope PLATFORM");
        }
        String value = resolved(cap);
        if (value == null) {
            return null;
        }
        try {
            int maximum = Integer.parseInt(value.trim());
            if (maximum >= 0) {
                return maximum;
            }
        } catch (NumberFormatException notANumber) {
            //reported below
        }
        throw new IllegalArgumentException("Configuration value <" + cap.key() + "> = <" + value
                + "> is not a limit (an integer of at least 0)");
    }

    /** The trimmed value; {@code null} for none or a blank one. */
    static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
