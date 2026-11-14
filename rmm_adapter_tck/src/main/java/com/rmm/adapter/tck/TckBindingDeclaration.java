package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterConventions;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigScope;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.IdentifierKind;
import com.rmm.adapter.api.RateLimit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rule for what a binding declares beyond its type. The TCK applies the
 * same rule as the host: what the host holds as
 * {@code DEFECT INSTANCE_INCONSISTENT}, the TCK case
 * {@code theBindingDeclarationOnlyNarrowsItsType} reports.
 *
 * <ul>
 *   <li>{@link AdapterInstance#capabilities()}: never {@code null}. If the
 *       binding declares its own, they only narrow the descriptor's:
 *       directions, phases, bulk operations and image reference forms are a
 *       subset, each supported feature only if the descriptor has it, a
 *       hierarchy model at most as rich ({@code NATIVE_TREE} over
 *       {@code PATH_ATTRIBUTE} over {@code FLAT}), a rate limit at most as
 *       fast and with at most as many retries (a descriptor without a rate
 *       limit accepts any).</li>
 *   <li>{@link AdapterInstance#connectionKeys()}: never {@code null}, at
 *       most {@value #MAX_KEYS}, no {@code null} entry, names
 *       {@code [a-z0-9.-]{1,64}}, only {@link #ALLOWED_TYPES} in scope
 *       {@code BINDING}, not reserved ({@link #isReserved}), no name of the
 *       type or of the host ({@link #HOST_KEYS}), no duplicate, a
 *       {@code SECRET} with a secret name and without a default, a secret
 *       name only for a {@code SECRET}, a description of at most
 *       {@value #MAX_DESCRIPTION_LENGTH} characters.</li>
 *   <li>{@link AdapterInstance#identifierKinds()}: never {@code null}, nor
 *       the list; no {@code null} entry, each name at most once, at most
 *       {@value #MAX_IDENTIFIER_KINDS} kinds, a pattern of at most
 *       {@value #MAX_PATTERN_LENGTH} characters and every other field of at
 *       most {@value #MAX_KIND_FIELD_LENGTH}. The lengths are bounded
 *       because the host runs the pattern over the values of artifacts.</li>
 * </ul>
 */
final class TckBindingDeclaration {

    /** Maximum number of keys a binding declares beyond its type (same as the host). */
    static final int MAX_KEYS = 64;
    /** Maximum length of the description of a binding key (same as the host). */
    static final int MAX_DESCRIPTION_LENGTH = 500;
    /** The name of a binding key (same as the host). */
    static final Pattern KEY_NAME = Pattern.compile("[a-z0-9.-]{1,64}");
    /** The types a binding key may have (same as the host). */
    static final Set<ConfigType> ALLOWED_TYPES = Set.copyOf(EnumSet.of(ConfigType.STRING, ConfigType.INTEGER,
            ConfigType.BOOLEAN, ConfigType.SECRET));
    /** The keys the host itself keeps on every connection. */
    static final Set<String> HOST_KEYS = Set.of("webhook.secret", "webhook.signing-secret", "webhook.signature",
            "webhook.replay-window-seconds",
            "webhook.debounce-seconds", "project-spaces", "conversion.delete-create", "attachments.enabled",
            "variability.enabled", "images.enabled", "orphaned-elements", "orphaned-elements.propagate",
            "orphaned-elements.max-delete-percent");
    /** Suffix of the caps of a type ({@code <name>.max}); only a type declares them. */
    static final String CAP_SUFFIX = ".max";
    /** Prefix of the names that belong to Mirador; no binding key starts with it. */
    static final String RESERVED_PREFIX = "rmm.";
    /** Maximum number of identifier kinds a binding declares. */
    static final int MAX_IDENTIFIER_KINDS = 32;
    /** Maximum length of the pattern of a binding's identifier kind. */
    static final int MAX_PATTERN_LENGTH = 256;
    /** Maximum length of every other field of a binding's identifier kind. */
    static final int MAX_KIND_FIELD_LENGTH = 500;

    private TckBindingDeclaration() {
    }

    /** How this instance's declaration violates the host's rule; empty if the host accepts it. */
    static List<String> refusals(AdapterDescriptor descriptor, AdapterInstance instance) {
        List<String> refused = new ArrayList<>();
        Optional<AdapterCapabilities> own = instance.capabilities();
        if (own == null) {
            refused.add("capabilities() returned null instead of an Optional");
        } else if (own.isPresent()) {
            notNarrower(refused, descriptor.capabilities(), own.get());
        }
        List<ConfigKey> keys = instance.connectionKeys();
        if (keys == null) {
            refused.add("connectionKeys() returned null instead of a list");
        } else {
            keyRefusals(refused, descriptor, keys);
        }
        Optional<List<IdentifierKind>> kinds = instance.identifierKinds();
        if (kinds == null || kinds.isPresent() && kinds.get() == null) {
            refused.add("identifierKinds() returned null instead of an Optional of a list");
        } else if (kinds.isPresent()) {
            kindRefusals(refused, kinds.get());
        }
        return refused;
    }

    /** Whether only a type may declare this name: HTTP path limits, {@code <name>.max} and {@code rmm.…}. */
    static boolean isReserved(String name) {
        return AdapterConventions.HTTP_RATE_PER_SECOND_KEY.equals(name)
                || AdapterConventions.HTTP_RETRIES_KEY.equals(name)
                || name.endsWith(CAP_SUFFIX) || name.startsWith(RESERVED_PREFIX);
    }

    private static void notNarrower(List<String> refused, AdapterCapabilities ofType, AdapterCapabilities own) {
        List<String> wider = new ArrayList<>();
        notWider(wider, "directions", ofType.directions(), own.directions());
        if (richness(own.hierarchyModel()) > richness(ofType.hierarchyModel())) {
            wider.add("hierarchyModel " + own.hierarchyModel() + " richer than " + ofType.hierarchyModel());
        }
        notWider(wider, "supportsRelationshipUpdate", ofType.supportsRelationshipUpdate(),
                own.supportsRelationshipUpdate());
        notWider(wider, "supportsArtifactTypeChange", ofType.supportsArtifactTypeChange(),
                own.supportsArtifactTypeChange());
        notWider(wider, "supportsPartialUpdate", ofType.supportsPartialUpdate(), own.supportsPartialUpdate());
        notWider(wider, "supportsDeltaRead", ofType.supportsDeltaRead(), own.supportsDeltaRead());
        notWider(wider, "reads", ofType.reads(), own.reads());
        notWider(wider, "writes", ofType.writes(), own.writes());
        notWider(wider, "bulkOperations", ofType.bulkOperations(), own.bulkOperations());
        notWider(wider, "imageReferenceForms", ofType.imageReferenceForms(), own.imageReferenceForms());
        RateLimit typeRate = ofType.rateLimit();
        RateLimit ownRate = own.rateLimit();
        if (ownRate != null && typeRate != null && (ownRate.maxRequestsPerSecond() > typeRate.maxRequestsPerSecond()
                || ownRate.maxRetries() > typeRate.maxRetries())) {
            wider.add("rateLimit " + ownRate + " above " + typeRate);
        }
        if (!wider.isEmpty()) {
            refused.add("the capabilities of the binding do more than its descriptor (a binding may only narrow"
                    + " them): " + String.join("; ", wider));
        }
    }

    private static int richness(HierarchyModel model) {
        return switch (model) {
            case FLAT -> 0;
            case PATH_ATTRIBUTE -> 1;
            case NATIVE_TREE -> 2;
        };
    }

    private static <T> void notWider(List<String> wider, String name, Set<T> ofType, Set<T> own) {
        Set<T> extra = new LinkedHashSet<>(own);
        extra.removeAll(ofType);
        if (!extra.isEmpty()) {
            wider.add(name + " " + extra + " not declared by the descriptor");
        }
    }

    private static void notWider(List<String> wider, String name, boolean ofType, boolean own) {
        if (own && !ofType) {
            wider.add(name + " not supported by the descriptor");
        }
    }

    private static void keyRefusals(List<String> refused, AdapterDescriptor descriptor, List<ConfigKey> keys) {
        if (keys.size() > MAX_KEYS) {
            refused.add("connectionKeys() declares " + keys.size() + " keys, at most " + MAX_KEYS);
            return;
        }
        Set<String> ofType = new HashSet<>();
        for (ConfigKey key : descriptor.configKeys()) {
            ofType.add(key.key().trim().toLowerCase(Locale.ROOT));
        }
        Set<String> seen = new HashSet<>();
        for (ConfigKey key : keys) {
            String refusal = key == null ? "a key is null" : keyRefusal(key, ofType, seen);
            if (refusal != null) {
                refused.add("connection key " + refusal);
            }
        }
    }

    /** The first violation of a key, in the host's order of checks; null if there is none. */
    private static String keyRefusal(ConfigKey key, Set<String> ofType, Set<String> seen) {
        String name = key.key();
        boolean secretName = MapConfig.SECRET_SUFFIX.matcher(name).find();
        if (!KEY_NAME.matcher(name).matches()) {
            return "<" + shown(name) + "> is not a key name ([a-z0-9.-]{1,64})";
        }
        if (!ALLOWED_TYPES.contains(key.type())) {
            return "<" + name + "> is of type " + key.type() + " (a binding declares only " + ALLOWED_TYPES
                    + "; CERTIFICATE, CLIENT_IDENTITY, PATH and URL only the descriptor)";
        }
        if (key.scope() != ConfigScope.BINDING) {
            return "<" + name + "> is in scope " + key.scope() + " (only BINDING)";
        }
        if (isReserved(name)) {
            return "<" + name + "> is reserved for the type (http.rate.per.second, http.retries, <name>"
                    + CAP_SUFFIX + " and " + RESERVED_PREFIX + "…)";
        }
        if (ofType.contains(name) || HOST_KEYS.contains(name)) {
            return "<" + name + "> is already declared by the " + (ofType.contains(name) ? "descriptor" : "host");
        }
        if (!seen.add(name)) {
            return "<" + name + "> is declared twice";
        }
        if (key.type() == ConfigType.SECRET && !secretName) {
            return "<" + name + "> is a SECRET without a secret name (it must end in password, passwd, secret,"
                    + " token, credential(s), apikey or api.key, so that the host seals it)";
        }
        if (key.type() != ConfigType.SECRET && secretName) {
            return "<" + name + "> has a secret name but is of type " + key.type() + " (only a SECRET may, so that"
                    + " its value is masked)";
        }
        if (key.type() == ConfigType.SECRET && key.defaultValue() != null && !key.defaultValue().isEmpty()) {
            return "<" + name + "> is a SECRET with a default value (a secret never has one)";
        }
        if (key.description() != null && key.description().length() > MAX_DESCRIPTION_LENGTH) {
            return "<" + name + "> has a description of " + key.description().length() + " characters (at most "
                    + MAX_DESCRIPTION_LENGTH + ")";
        }
        return null;
    }

    private static void kindRefusals(List<String> refused, List<IdentifierKind> kinds) {
        if (kinds.size() > MAX_IDENTIFIER_KINDS) {
            refused.add("identifierKinds() declares " + kinds.size() + " identifier kinds, at most "
                    + MAX_IDENTIFIER_KINDS);
            return;
        }
        Set<String> names = new HashSet<>();
        for (IdentifierKind kind : kinds) {
            if (kind == null) {
                refused.add("an identifier kind is null");
            } else if (!names.add(kind.name())) {
                refused.add("identifier kind <" + shown(kind.name()) + "> is declared twice");
            } else {
                String tooLong = tooLongField(kind);
                if (tooLong != null) {
                    refused.add("identifier kind <" + shown(kind.name()) + "> has " + tooLong);
                }
            }
        }
    }

    /** The first field of a kind that is too long; null if none is (same as the host). */
    static String tooLongField(IdentifierKind kind) {
        if (kind.pattern() != null && kind.pattern().length() > MAX_PATTERN_LENGTH) {
            return "a pattern of " + kind.pattern().length() + " characters (at most " + MAX_PATTERN_LENGTH + ")";
        }
        String[][] fields = {{"name", kind.name()}, {"fieldLabel", kind.fieldLabel()}, {"format", kind.format()},
                {"sample", kind.sample()}, {"description", kind.description()}};
        for (String[] field : fields) {
            if (field[1] != null && field[1].length() > MAX_KIND_FIELD_LENGTH) {
                return "a " + field[0] + " of " + field[1].length() + " characters (at most " + MAX_KIND_FIELD_LENGTH
                        + ")";
            }
        }
        return null;
    }

    /** A name as shown in a message: at most 64 characters, one line. */
    private static String shown(String name) {
        String line = name.replaceAll("\\p{Cntrl}", "?");
        return line.length() <= 64 ? line : line.substring(0, 63) + "…";
    }
}
