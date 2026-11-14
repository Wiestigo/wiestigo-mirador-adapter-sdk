package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterConventions;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.ImageReferenceForm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static com.rmm.adapter.tck.AdapterContractTest.PLUGIN_MANIFEST;
import static com.rmm.adapter.tck.AdapterContractTest.PLUGIN_MAPPING_TEMPLATE;

/**
 * The manifest of a plugin unit (case 6), which the host reads without
 * running code of the unit, and the check that it matches the descriptor of
 * the adapter.
 */
final class PluginManifest {

    /**
     * The minor version of the contract that introduced image reference
     * forms, the type {@code CLIENT_IDENTITY} and the key
     * {@value AdapterConventions#CONNECT_TIMEOUT_KEY}. Fixed, not
     * {@link AdapterApiVersion#MINOR}: it does not grow with later minor
     * versions.
     */
    static final int MINOR_OF_FORMS_CLIENT_IDENTITY_AND_CONNECT_TIMEOUT = 1;

    /**
     * The rule of the host for the scheme name of an image reference form,
     * narrower than the grammar of the contract ({@link ImageReferenceForm},
     * which also allows {@code +}, {@code .} and a single letter): lower-case
     * letters, digits and {@code -}, starting with a letter, 2 to 32
     * characters. The host refuses a unit with another scheme on upload. A
     * copy of the rule of the host, because the TCK does not depend on the
     * host.
     */
    static final Pattern HOST_IMAGE_SCHEME = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");

    private final Map<String, Object> manifest;

    private PluginManifest(Map<String, Object> manifest) {
        this.manifest = manifest;
    }

    /** Reads the text of a manifest; anything but a JSON object is a violation. */
    static PluginManifest parse(String text) {
        try {
            return new PluginManifest(TckJson.object(text));
        } catch (IllegalArgumentException e) {
            throw new TckViolation("Case 6: " + PLUGIN_MANIFEST + " is no JSON object: " + e.getMessage());
        }
    }

    /**
     * The manifest matches the descriptor: {@code typeKey}, {@code name},
     * {@code version}, {@code apiMajor}, {@code apiMinor}, a {@code vendor},
     * the {@code configKeys} in order, the directions, the hierarchy model
     * and the image reference forms. It names neither
     * {@code mappingTemplate} nor {@code legacyMappingId}.
     */
    void requireMatches(AdapterDescriptor descriptor) {
        requireManifest(manifest, "typeKey", descriptor.systemKey());
        requireManifest(manifest, "name", descriptor.displayName());
        requireManifest(manifest, "version", descriptor.adapterVersion());
        requireManifest(manifest, "apiMajor", String.valueOf(AdapterApiVersion.MAJOR));
        requireApiMinor(manifest, descriptor);
        if (manifestValue(manifest, "vendor").isBlank()) {
            throw new TckViolation("Case 6: " + PLUGIN_MANIFEST + " names no vendor");
        }
        for (String unread : List.of("mappingTemplate", "legacyMappingId")) {
            if (manifest.containsKey(unread)) {
                throw new TckViolation("Case 6: " + PLUGIN_MANIFEST + " names <" + unread + ">, which the host does"
                        + " not read: the template is always " + PLUGIN_MAPPING_TEMPLATE + ", and the mapping of a"
                        + " tenant is the one of its connection — leave the field out");
            }
        }
        List<String> declared = new ArrayList<>();
        for (ConfigKey key : descriptor.configKeys()) {
            declared.add(key.key() + " " + key.type() + " " + key.scope() + " " + key.isRequired());
        }
        List<String> listed = new ArrayList<>();
        Object keys = manifest.get("configKeys");
        if (keys instanceof List<?> entries) {
            for (Object entry : entries) {
                @SuppressWarnings("unchecked")
                Map<String, Object> key = entry instanceof Map<?, ?> ? (Map<String, Object>) entry : Map.of();
                listed.add(manifestValue(key, "key") + " " + manifestValue(key, "type") + " "
                        + manifestValue(key, "scope") + " " + manifestValue(key, "required"));
            }
        }
        if (!declared.equals(listed)) {
            throw new TckViolation("Case 6: the configKeys of " + PLUGIN_MANIFEST + " differ from the descriptor"
                    + " (key, type, scope, required, in order) — descriptor " + declared + ", manifest " + listed);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = manifest.get("capabilities") instanceof Map<?, ?> found
                ? (Map<String, Object>) found : Map.of();
        Set<String> directions = new TreeSet<>();
        if (capabilities.get("directions") instanceof List<?> named) {
            named.forEach(direction -> directions.add(String.valueOf(direction)));
        }
        Set<String> described = new TreeSet<>();
        descriptor.capabilities().directions().forEach(direction -> described.add(direction.name()));
        if (!described.equals(directions)) {
            throw new TckViolation("Case 6: the directions of " + PLUGIN_MANIFEST + " " + directions
                    + " differ from the descriptor " + described);
        }
        requireManifest(capabilities, "hierarchyModel", descriptor.capabilities().hierarchyModel().name());
        requireImageReferenceForms(capabilities, descriptor);
    }

    /**
     * {@code apiMinor}: a whole number like {@code apiMajor}, equal to
     * {@link AdapterDescriptor#apiMinorVersion()} and not greater than the
     * minor version of this contract. The host reads it before any code of
     * the unit and refuses a higher one than its own. At least
     * {@value #MINOR_OF_FORMS_CLIENT_IDENTITY_AND_CONNECT_TIMEOUT} if the
     * descriptor declares something that only came with it (image reference
     * forms, a key of type {@code CLIENT_IDENTITY}, the key
     * {@value AdapterConventions#CONNECT_TIMEOUT_KEY}): an older host does
     * not know it.
     */
    private static void requireApiMinor(Map<String, Object> manifest, AdapterDescriptor descriptor) {
        Object value = manifest.get("apiMinor");
        if (!(value instanceof BigDecimal number) || number.signum() < 0
                || number.stripTrailingZeros().scale() > 0) {
            throw new TckViolation("Case 6: " + PLUGIN_MANIFEST + " names no apiMinor as a whole number ("
                    + (value == null ? "missing" : "<" + value + ">") + ") — name the minor version of the adapter"
                    + " API the adapter is built against, the same as descriptor().apiMinorVersion() <"
                    + descriptor.apiMinorVersion() + ">: the host reads it before any code of the unit runs");
        }
        requireManifest(manifest, "apiMinor", String.valueOf(descriptor.apiMinorVersion()));
        if (descriptor.apiMinorVersion() > AdapterApiVersion.MINOR) {
            throw new TckViolation("Case 6: the adapter is built against minor version " + descriptor.apiMinorVersion()
                    + " of the adapter API, this TCK knows " + AdapterApiVersion.MAJOR + "." + AdapterApiVersion.MINOR
                    + " — a host of this version refuses the unit when loading it");
        }
        List<String> laterUses = usesOfMinorOne(descriptor);
        if (!laterUses.isEmpty()
                && descriptor.apiMinorVersion() < MINOR_OF_FORMS_CLIENT_IDENTITY_AND_CONNECT_TIMEOUT) {
            throw new TckViolation("Case 6: the descriptor declares " + String.join(", ", laterUses) + ", which came"
                    + " with minor version " + MINOR_OF_FORMS_CLIENT_IDENTITY_AND_CONNECT_TIMEOUT + " of the adapter"
                    + " API, but apiMinor is " + descriptor.apiMinorVersion() + " — build the descriptor with apiMinor"
                    + " at least " + MINOR_OF_FORMS_CLIENT_IDENTITY_AND_CONNECT_TIMEOUT + " (the constructor with"
                    + " eight arguments) and name the same in " + PLUGIN_MANIFEST);
        }
    }

    /** What the descriptor declares from minor version 1: image reference forms, client identity, connect timeout. */
    private static List<String> usesOfMinorOne(AdapterDescriptor descriptor) {
        List<String> uses = new ArrayList<>();
        if (!descriptor.capabilities().imageReferenceForms().isEmpty()) {
            uses.add("image reference forms");
        }
        for (ConfigKey key : descriptor.configKeys()) {
            if (key.type() == ConfigType.CLIENT_IDENTITY) {
                uses.add("the key <" + key.key() + "> of type CLIENT_IDENTITY");
            }
            if (AdapterConventions.CONNECT_TIMEOUT_KEY.equals(key.key())) {
                uses.add("the key <" + key.key() + ">");
            }
        }
        return uses;
    }

    /**
     * {@code capabilities.imageReferenceForms}: exactly the image reference
     * forms of the descriptor, each with {@code kind} and {@code value} as
     * {@link ImageReferenceForm} holds them, also as an empty list. The
     * console shows them before the release, and the release of a unit (by
     * its SHA-256) covers exactly these forms. A scheme keeps to the rule of
     * the host ({@link #HOST_IMAGE_SCHEME}).
     */
    private static void requireImageReferenceForms(Map<String, Object> capabilities, AdapterDescriptor descriptor) {
        Set<String> described = new TreeSet<>();
        descriptor.capabilities().imageReferenceForms()
                .forEach(form -> described.add(form.kind().name() + " " + form.value()));
        if (!(capabilities.get("imageReferenceForms") instanceof List<?> named)) {
            throw new TckViolation("Case 6: " + PLUGIN_MANIFEST + " names no capabilities.imageReferenceForms as a"
                    + " list — name the image reference forms of the descriptor " + described
                    + " as [{\"kind\": …, \"value\": …}], an empty list if there are none");
        }
        List<String> listed = new ArrayList<>();
        for (Object entry : named) {
            @SuppressWarnings("unchecked")
            Map<String, Object> form = entry instanceof Map<?, ?> ? (Map<String, Object>) entry : Map.of();
            listed.add(manifestValue(form, "kind") + " " + manifestValue(form, "value"));
        }
        if (listed.size() != described.size() || !described.equals(new TreeSet<>(listed))) {
            throw new TckViolation("Case 6: the imageReferenceForms of " + PLUGIN_MANIFEST + " " + listed
                    + " differ from the descriptor " + described + " (kind and value as the descriptor holds them,"
                    + " each once): a release covers exactly the forms of the manifest");
        }
        for (ImageReferenceForm form : descriptor.capabilities().imageReferenceForms()) {
            if (form.kind() == ImageReferenceForm.Kind.SCHEME
                    && !HOST_IMAGE_SCHEME.matcher(form.value().trim().toLowerCase(Locale.ROOT)).matches()) {
                throw new TckViolation("Case 6: the imageReferenceForms of " + PLUGIN_MANIFEST + " name the scheme <"
                        + form.value() + ">, which the host never honours: lower-case letters, digits and '-',"
                        + " starting with a letter, 2 to 32 characters — the host refuses the unit at upload"
                        + " (MANIFEST_IMAGE_FORM_REFUSED)");
            }
        }
    }

    private static void requireManifest(Map<String, Object> manifest, String field, String expected) {
        String value = manifestValue(manifest, field);
        if (!value.equals(expected)) {
            throw new TckViolation("Case 6: " + PLUGIN_MANIFEST + " names " + field + " <" + value
                    + ">, the descriptor <" + expected + ">");
        }
    }

    /** A manifest value as text (a number in plain notation, without trailing zeros); empty if it is missing. */
    private static String manifestValue(Map<String, Object> manifest, String field) {
        Object value = manifest.get(field);
        if (value == null || value == TckJson.NULL) {
            return "";
        }
        if (value instanceof BigDecimal number) {
            return number.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
