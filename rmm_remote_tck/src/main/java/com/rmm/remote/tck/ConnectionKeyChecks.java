package com.rmm.remote.tck;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static com.rmm.remote.tck.WireJson.textOf;

/**
 * The rules for the additional connection keys of a descriptor
 * ({@code connectionKeys} in {@code docs/REMOTE_PROTOCOL.md},
 * "GET /descriptor"), as the host checks them.
 */
final class ConnectionKeyChecks {

    /** The types a service may declare for additional connection keys ({@code connectionKeys}). */
    static final Set<String> CONNECTION_KEY_TYPES = Set.of("STRING", "INTEGER", "BOOLEAN", "SECRET");
    static final Pattern CONNECTION_KEY_NAME = Pattern.compile("[a-z0-9.-]{1,64}");
    static final int MAX_CONNECTION_KEYS = 64;
    /** Maximum length of a key's description (the host's limit). */
    static final int MAX_DESCRIPTION_LENGTH = 500;
    /** The keys of the type {@code REMOTE}; a service must not declare them again. */
    static final Set<String> KEYS_OF_THE_TYPE = Set.of("url", "tls.trust", "tls.client", "timeout.connect",
            "timeout.request");
    /** The keys Mirador keeps at every connection; a service must not declare them again. */
    static final Set<String> KEYS_OF_THE_RMM = Set.of("webhook.secret", "webhook.signing-secret", "webhook.signature",
            "webhook.replay-window-seconds",
            "webhook.debounce-seconds", "project-spaces", "conversion.delete-create", "attachments.enabled",
            "variability.enabled", "images.enabled", "orphaned-elements", "orphaned-elements.propagate",
            "orphaned-elements.max-delete-percent");
    /** The limits of the host's HTTP path; only a type declares them and any {@code <name>.max}. */
    static final Set<String> RESERVED_KEYS = Set.of("http.rate.per.second", "http.retries");
    /** The suffix of a type's upper limits ({@code <name>.max}); a service declares no such name. */
    static final String CAP_SUFFIX = ".max";
    /** Secret names (as in the host's secret store): a key has such a name exactly when it is a {@code SECRET}. */
    static final Pattern SECRET_NAME =
            Pattern.compile("(?i)(password|passwd|secret|token|credentials?|apikey|api.key)$");
    /** The namespace of Mirador; no key of a service starts with it. */
    static final String RESERVED_PREFIX = "rmm.";

    private ConnectionKeyChecks() {
    }

    /** Requires that the connection keys of the descriptor follow the rules; {@code where} starts the message. */
    static void require(String where, JsonNode descriptor) {
        JsonNode keys = descriptor.path("connectionKeys");
        if (keys.isMissingNode() || keys.isNull()) {
            return;
        }
        if (!keys.isArray() || keys.size() > MAX_CONNECTION_KEYS) {
            throw new ServiceContractViolation(where + ": connectionKeys must be an array of at most "
                    + MAX_CONNECTION_KEYS + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode key : keys) {
            String name = textOf(key, "key");
            String type = textOf(key, "type");
            String refusal = refusalOf(key, name, type, seen);
            if (refusal != null) {
                throw new ServiceContractViolation(where + " declares the connection key <" + name + "> of type <"
                        + type + ">: " + refusal + " (docs/REMOTE_PROTOCOL.md, \"GET /descriptor\", connectionKeys;"
                        + " Mirador holds the connection DEFECT INSTANCE_INCONSISTENT)");
            }
        }
    }

    /** The first violation of a key; null when there is none. A name that is free so far joins {@code seen}. */
    private static String refusalOf(JsonNode key, String name, String type, Set<String> seen) {
        String refusal = declarationRefusal(key, name, type);
        if (refusal == null) {
            refusal = takenNameRefusal(name, seen);
        }
        if (refusal == null) {
            refusal = secretRefusal(key, name, type);
        }
        return refusal;
    }

    /** What is wrong with the form of the declaration: name, type, scope or description; null when nothing is. */
    private static String declarationRefusal(JsonNode key, String name, String type) {
        String description = textOf(key, "description");
        if (!CONNECTION_KEY_NAME.matcher(name).matches()) {
            return "names are [a-z0-9.-]{1,64}";
        }
        if (name.startsWith(RESERVED_PREFIX)) {
            return "a key is declared relative to the connection, rmm. is the namespace of Mirador";
        }
        if (!CONNECTION_KEY_TYPES.contains(type)) {
            return "types are " + CONNECTION_KEY_TYPES + " (CERTIFICATE, CLIENT_IDENTITY, PATH and URL are declared"
                    + " only by the type REMOTE)";
        }
        if (key.hasNonNull("scope") && !key.get("scope").asString("").equals("BINDING")) {
            return "the scope is BINDING or absent";
        }
        if (description.isBlank()) {
            return "a description is required";
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            return "the description has " + description.length() + " characters, at most " + MAX_DESCRIPTION_LENGTH;
        }
        return null;
    }

    /** Why the name is taken: reserved, a key of the type or of Mirador, or declared before; null when it is free. */
    private static String takenNameRefusal(String name, Set<String> seen) {
        if (RESERVED_KEYS.contains(name) || name.endsWith(CAP_SUFFIX)) {
            return "the name is reserved for the type (" + RESERVED_KEYS + " and <name>" + CAP_SUFFIX + ")";
        }
        if (KEYS_OF_THE_TYPE.contains(name)) {
            return "the name is already a key of the type REMOTE " + KEYS_OF_THE_TYPE;
        }
        if (KEYS_OF_THE_RMM.contains(name)) {
            return "the name is already a key of Mirador at every connection";
        }
        if (!seen.add(name)) {
            return "the key is declared twice";
        }
        return null;
    }

    /** Where secret name and type {@code SECRET} disagree, or a {@code SECRET} has a default; null otherwise. */
    private static String secretRefusal(JsonNode key, String name, String type) {
        boolean secretName = SECRET_NAME.matcher(name).find();
        if (type.equals("SECRET") && !secretName) {
            return "a SECRET needs a secret name (ending in password, passwd, secret, token, credential(s), apikey"
                    + " or api.key), so that Mirador seals it";
        }
        if (!type.equals("SECRET") && secretName) {
            return "a secret name is reserved for a SECRET, so that its value is masked";
        }
        if (type.equals("SECRET") && !key.path("required").asBoolean(false) && !textOf(key, "default").isEmpty()) {
            return "a SECRET has no default";
        }
        return null;
    }
}
