package com.rmm.remote.tck;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.rmm.remote.tck.WireJson.textOf;
import static com.rmm.remote.tck.WireJson.textsOf;

/**
 * The rules of a version 4 descriptor ({@code docs/REMOTE_PROTOCOL.md},
 * "GET /descriptor"), checked the way the host reads a descriptor before it
 * binds the connection (case 4).
 */
final class DescriptorChecks {

    static final Set<String> DIRECTIONS = Set.of("TO_RMM", "FROM_RMM");
    static final Set<String> HIERARCHY_MODELS = Set.of("NATIVE_TREE", "PATH_ATTRIBUTE", "FLAT");
    static final Set<String> PHASES = Set.of("ARTIFACTS", "RELATIONSHIPS", "COMMENTS", "ATTACHMENTS",
            "VARIABILITY");
    /** The bulk operations, each with the phase that {@code writes} must declare for it. */
    static final Map<String, String> BULK_OPERATIONS = Map.of("ARTIFACT_CREATE", "ARTIFACTS",
            "ARTIFACT_UPDATE", "ARTIFACTS", "ARTIFACT_HIERARCHY_UPDATE", "ARTIFACTS", "ARTIFACT_DELETE", "ARTIFACTS",
            "RELATIONSHIP_CREATE", "RELATIONSHIPS", "RELATIONSHIP_UPDATE", "RELATIONSHIPS",
            "RELATIONSHIP_DELETE", "RELATIONSHIPS", "COMMENT_CREATE", "COMMENTS");

    private DescriptorChecks() {
    }

    /** Requires that the descriptor of the binding follows every rule of a version 4 descriptor. */
    static void requireVersion4(RemoteServiceIdentities.Identity identity, JsonNode descriptor) {
        String where = "Case 4: the descriptor of binding " + identity.bindingId();
        requireProtocolVersion(where, descriptor);
        for (String field : List.of("systemKey", "displayName", "adapterVersion")) {
            requireText(where, descriptor, "", field);
        }
        JsonNode capabilities = descriptor.path("capabilities");
        if (!capabilities.isObject()) {
            throw new ServiceContractViolation(where + " carries no capabilities object");
        }
        Set<String> directions = requireDirections(where, capabilities);
        String hierarchy = requireHierarchyModel(where, capabilities);
        requirePhases(where, capabilities, "reads", directions.contains("TO_RMM"));
        Set<String> writes = requirePhases(where, capabilities, "writes", directions.contains("FROM_RMM"));
        requireBulkOperations(where, capabilities, writes);
        if (capabilities.path("supportsRelationshipUpdate").asBoolean(false) && !writes.contains("RELATIONSHIPS")) {
            throw new ServiceContractViolation(where + " declares supportsRelationshipUpdate, but writes does not"
                    + " declare RELATIONSHIPS");
        }
        requireRateLimit(where, capabilities);
        requirePathHierarchy(where, descriptor, hierarchy);
        IdentifierKindChecks.require(where, descriptor);
        ConnectionKeyChecks.require(where, descriptor);
    }

    private static void requireProtocolVersion(String where, JsonNode descriptor) {
        JsonNode version = descriptor.path("protocolVersion");
        if (!version.isIntegralNumber() || version.asInt(0) != RemoteServiceContractTest.PROTOCOL_VERSION) {
            throw new ServiceContractViolation(where + " names protocolVersion " + version + " — Mirador speaks"
                    + " exactly version " + RemoteServiceContractTest.PROTOCOL_VERSION
                    + " and refuses an older service (PROTOCOL_TOO_OLD)");
        }
    }

    /** A mandatory field with non-empty text, as the host reads it. */
    private static void requireText(String where, JsonNode node, String prefix, String field) {
        if (textOf(node, field).isBlank()) {
            throw new ServiceContractViolation(where + " is missing <" + prefix + field + "> — a non-empty text;"
                    + " Mirador does not bind the connection without it");
        }
    }

    /** The declared directions: at least one, and only known ones. */
    private static Set<String> requireDirections(String where, JsonNode capabilities) {
        Set<String> directions = textsOf(capabilities.path("directions"), where + ": capabilities.directions");
        if (directions.isEmpty() || !DIRECTIONS.containsAll(directions)) {
            throw new ServiceContractViolation(where + " declares the directions " + directions + " — at least one"
                    + " of " + DIRECTIONS);
        }
        return directions;
    }

    /** The declared hierarchy model: a known one. */
    private static String requireHierarchyModel(String where, JsonNode capabilities) {
        String hierarchy = capabilities.path("hierarchyModel").asString("");
        if (!HIERARCHY_MODELS.contains(hierarchy)) {
            throw new ServiceContractViolation(where + " declares the hierarchyModel <" + hierarchy + "> — one of "
                    + HIERARCHY_MODELS);
        }
        return hierarchy;
    }

    /**
     * The phases declared in {@code reads} or {@code writes}: a mandatory
     * array that holds {@code ARTIFACTS} exactly when the service declares
     * the direction of the field, and nothing without it.
     */
    private static Set<String> requirePhases(String where, JsonNode capabilities, String field, boolean direction) {
        JsonNode phases = capabilities.path(field);
        if (!phases.isArray()) {
            throw new ServiceContractViolation(where + " has no capabilities." + field + " — the field is mandatory,"
                    + " also as an empty array");
        }
        Set<String> declared = textsOf(phases, where + ": capabilities." + field);
        if (!PHASES.containsAll(declared) || direction != declared.contains("ARTIFACTS")
                || !direction && !declared.isEmpty()) {
            throw new ServiceContractViolation(where + " declares " + field + " " + declared + " — with the"
                    + " direction at least ARTIFACTS, without it empty, only " + PHASES);
        }
        return declared;
    }

    /** Every declared bulk operation is a known one whose phase {@code writes} declares. */
    private static void requireBulkOperations(String where, JsonNode capabilities, Set<String> writes) {
        Set<String> bulk = textsOf(capabilities.path("bulkOperations"), where + ": capabilities.bulkOperations");
        for (String operation : bulk) {
            if (!BULK_OPERATIONS.containsKey(operation)) {
                throw new ServiceContractViolation(where + " declares the unknown bulk operation <" + operation
                        + "> — only " + BULK_OPERATIONS.keySet());
            }
            if (!writes.contains(BULK_OPERATIONS.get(operation))) {
                throw new ServiceContractViolation(where + " declares the bulk operation " + operation + ", but"
                        + " writes does not declare " + BULK_OPERATIONS.get(operation) + " — a bulk operation needs"
                        + " its phase");
            }
        }
    }

    private static void requireRateLimit(String where, JsonNode capabilities) {
        JsonNode rateLimit = capabilities.path("rateLimit");
        if (rateLimit.isObject() && (rateLimit.path("maxRequestsPerSecond").asInt(0) <= 0
                || rateLimit.path("maxRetries").asInt(0) < 0)) {
            throw new ServiceContractViolation(where + " declares the rateLimit " + rateLimit + " —"
                    + " maxRequestsPerSecond at least 1, maxRetries not negative");
        }
    }

    /** A {@code pathHierarchy} object is complete; {@code PATH_ATTRIBUTE} needs one. */
    private static void requirePathHierarchy(String where, JsonNode descriptor, String hierarchy) {
        JsonNode pathHierarchy = descriptor.path("pathHierarchy");
        if (pathHierarchy.isObject()) {
            //As the host reads it: an object that is sent must be complete, even without PATH_ATTRIBUTE
            for (String field : List.of("attributeName", "attributeLabel", "separator")) {
                requireText(where, pathHierarchy, "pathHierarchy.", field);
            }
        } else if (hierarchy.equals("PATH_ATTRIBUTE")) {
            throw new ServiceContractViolation(where + " declares PATH_ATTRIBUTE without a pathHierarchy object");
        }
    }
}
