package com.rmm.adapter.tck;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the TCK's stores ({@link TckDocuments}), written
 * without a library so that the TCK depends on no JSON parser. It reads
 * RFC 8259 (objects, lists, strings, numbers, {@code true}, {@code false},
 * {@code null}) and rejects the names the contract reserves
 * ({@link com.rmm.adapter.api.DocumentCollection}): {@code _id} at the top
 * level, at any depth names that start with {@code $} or contain a dot and,
 * as BSON does, names with the character NUL.
 *
 * <p>Values: {@link Map} (in field order), {@link List}, {@link String},
 * {@link BigDecimal}, {@link Boolean} and {@link #NULL}. Messages never show
 * the content, only position and reason, because a document can carry data
 * of the tool.</p>
 */
final class TckJson {

    /** The value {@code null}, as an object of its own so that a missing field stays distinguishable. */
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** No reasonable document nests deeper; an attack with a thousand brackets ends here. */
    private static final int MAX_DEPTH = 100;

    private final String text;
    private int position;

    private TckJson(String text) {
        this.text = text;
    }

    /**
     * Reads a JSON object.
     *
     * @throws IllegalArgumentException if the text is not a valid JSON object
     *                                  or uses a reserved name
     */
    static Map<String, Object> object(String json) {
        if (json == null) {
            throw new IllegalArgumentException("document json must not be null");
        }
        TckJson reader = new TckJson(json);
        reader.space();
        if (reader.position >= json.length() || json.charAt(reader.position) != '{') {
            throw new IllegalArgumentException("document must be a JSON object");
        }
        Object value = reader.value(0);
        reader.space();
        if (reader.position != json.length()) {
            throw reader.invalid("unexpected content after the object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid("nested deeper than " + MAX_DEPTH + " levels");
        }
        space();
        if (position >= text.length()) {
            throw invalid("unexpected end");
        }
        char next = text.charAt(position);
        return switch (next) {
            case '{' -> object(depth);
            case '[' -> list(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", NULL);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        position++;
        Map<String, Object> fields = new LinkedHashMap<>();
        space();
        if (peek('}')) {
            position++;
            return fields;
        }
        while (true) {
            space();
            if (!peek('"')) {
                throw invalid("expected a field name");
            }
            String name = string();
            requireAllowedName(name, depth);
            space();
            expect(':');
            fields.put(name, value(depth + 1));
            space();
            if (peek(',')) {
                position++;
                continue;
            }
            expect('}');
            return fields;
        }
    }

    private List<Object> list(int depth) {
        position++;
        List<Object> values = new ArrayList<>();
        space();
        if (peek(']')) {
            position++;
            return values;
        }
        while (true) {
            values.add(value(depth + 1));
            space();
            if (peek(',')) {
                position++;
                continue;
            }
            expect(']');
            return values;
        }
    }

    private String string() {
        position++;
        StringBuilder value = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw invalid("unterminated string");
            }
            char next = text.charAt(position++);
            if (next == '"') {
                return value.toString();
            }
            if (next < 0x20) {
                throw invalid("control character in a string");
            }
            if (next != '\\') {
                value.append(next);
                continue;
            }
            if (position >= text.length()) {
                throw invalid("unterminated escape");
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw invalid("short unicode escape");
                    }
                    int codeUnit = fourHexDigits(position);
                    if (codeUnit < 0) {
                        throw invalid("invalid unicode escape");
                    }
                    value.append((char) codeUnit);
                    position += 4;
                }
                default -> throw invalid("invalid escape");
            }
        }
    }

    /**
     * The value of the four hexadecimal digits ({@code 0-9}, {@code a-f},
     * {@code A-F}) that start at {@code start}; -1 if one of the four
     * characters is none. Stricter than {@code Integer.parseInt(text, 16)},
     * which also takes a sign and the digits of other scripts.
     */
    private int fourHexDigits(int start) {
        int value = 0;
        for (int index = start; index < start + 4; index++) {
            char c = text.charAt(index);
            int digit = c >= '0' && c <= '9' ? c - '0'
                    : c >= 'a' && c <= 'f' ? c - 'a' + 10
                    : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw invalid("unexpected token");
        }
        position += word.length();
        return value;
    }

    /** A number by the grammar of RFC 8259: {@code -?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?}. */
    private BigDecimal number() {
        int start = position;
        if (peek('-')) {
            position++;
        }
        if (peek('0')) {
            position++;
        } else if (digits() == 0) {
            throw invalid("unexpected token");
        }
        if (peek('.')) {
            position++;
            if (digits() == 0) {
                throw invalid("digits expected after the decimal point");
            }
        }
        if (peek('e') || peek('E')) {
            position++;
            if (peek('+') || peek('-')) {
                position++;
            }
            if (digits() == 0) {
                throw invalid("digits expected in the exponent");
            }
        }
        return new BigDecimal(text.substring(start, position));
    }

    private int digits() {
        int start = position;
        while (position < text.length() && Character.isDigit(text.charAt(position))
                && text.charAt(position) < 0x80) {
            position++;
        }
        return position - start;
    }

    private void space() {
        while (position < text.length()) {
            char next = text.charAt(position);
            if (next != ' ' && next != '\t' && next != '\n' && next != '\r') {
                return;
            }
            position++;
        }
    }

    private boolean peek(char wanted) {
        return position < text.length() && text.charAt(position) == wanted;
    }

    private void expect(char wanted) {
        if (!peek(wanted)) {
            throw invalid("expected '" + wanted + "'");
        }
        position++;
    }

    /** Rejects the names the contract reserves; the message shows the name, never a value. */
    private void requireAllowedName(String name, int depth) {
        if (name.indexOf('\0') >= 0) {
            //BSON writes names as C strings: a NUL would end the name in the middle
            throw new IllegalArgumentException("a field name contains the character NUL (\\u0000) — BSON, the format"
                    + " of the host's store, refuses it");
        }
        if (depth == 0 && name.equals("_id")) {
            throw new IllegalArgumentException("field name <_id> is reserved at the top level — the host keeps the"
                    + " id there");
        }
        if (name.startsWith("$") || name.indexOf('.') >= 0) {
            throw new IllegalArgumentException("field name <" + name + "> is reserved — names must not start with $"
                    + " or contain a dot");
        }
    }

    private IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("document is not valid JSON (" + reason + " at index " + position + ")");
    }
}
