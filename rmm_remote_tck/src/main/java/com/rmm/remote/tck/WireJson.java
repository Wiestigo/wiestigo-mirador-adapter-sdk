package com.rmm.remote.tck;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads values from the JSON a service answers, the way the host reads them,
 * and shortens a body for a violation message.
 */
final class WireJson {

    private WireJson() {
    }

    /** The text of a field as the host reads it; empty when it is missing or {@code null}. */
    static String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asString("");
    }

    /**
     * The texts of an array; empty when it is missing or {@code null}. A
     * value that is no array, or holds something else than texts, is a
     * violation that names {@code where}.
     */
    static Set<String> textsOf(JsonNode array, String where) {
        Set<String> texts = new LinkedHashSet<>();
        if (array.isMissingNode() || array.isNull()) {
            return texts;
        }
        if (!array.isArray()) {
            throw new ServiceContractViolation(where + " is no array: " + array);
        }
        for (JsonNode element : array) {
            if (!element.isString()) {
                throw new ServiceContractViolation(where + " holds a value that is no text: " + array);
            }
            texts.add(element.asString(""));
        }
        return texts;
    }

    /** Whether one of the array's elements is the text {@code value}. */
    static boolean contains(JsonNode array, String value) {
        for (JsonNode element : array) {
            if (value.equals(element.asString(""))) {
                return true;
            }
        }
        return false;
    }

    /** At most 300 characters of a body, for a message; {@code <none>} when there is none. */
    static String abbreviated(String text) {
        if (text == null) {
            return "<none>";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
