package com.example.remoteservice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of the wire protocol without a library (the template needs only
 * the JDK): reads a text into {@link Map} (object, field order is kept),
 * {@link List} (array), {@link String}, {@link Long} or {@link BigDecimal}
 * (number), {@link Boolean} and {@code null}, and writes the same values
 * back. In your own service, use any JSON library instead.
 */
final class Json {

    /** A text that is not JSON; the service answers 400. */
    static final class Unreadable extends RuntimeException {
        Unreadable(String message) {
            super(message);
        }
    }

    /** Maximum nesting depth the service reads (protects the stack). */
    private static final int MAX_DEPTH = 64;

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    /** Reads exactly one value; only whitespace may follow. */
    static Object parse(String text) {
        Json reader = new Json(text == null ? "" : text);
        Object value = reader.value(0);
        reader.skipWhitespace();
        if (reader.position != reader.text.length()) {
            throw new Unreadable("unexpected text after the JSON value at " + reader.position);
        }
        return value;
    }

    /** Reads an object; anything else is {@link Unreadable}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new Unreadable("the body is no JSON object");
        }
        return (Map<String, Object>) value;
    }

    /** Writes a value (Map, List, String, Number, Boolean, null). */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            quote(string, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                write(list.get(index), out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("no JSON value: " + value.getClass().getName());
        }
    }

    private static void quote(String string, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < string.length(); index++) {
            char c = string.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw new Unreadable("the JSON is nested deeper than " + MAX_DEPTH);
        }
        skipWhitespace();
        if (position >= text.length()) {
            throw new Unreadable("unexpected end of the JSON");
        }
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> object = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek('}')) {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw new Unreadable("expected a field name at " + position);
            }
            String name = string();
            skipWhitespace();
            expect(':');
            object.put(name, value(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                position++;
            } else {
                expect('}');
                return object;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> array = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek(']')) {
            position++;
            return array;
        }
        while (true) {
            array.add(value(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                position++;
            } else {
                expect(']');
                return array;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (position >= text.length()) {
                    break;
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw new Unreadable("a broken \\u escape at " + position);
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        } catch (NumberFormatException broken) {
                            throw new Unreadable("a broken \\u escape at " + position);
                        }
                        position += 4;
                    }
                    default -> throw new Unreadable("an unknown escape \\" + escaped + " at " + position);
                }
            } else if (c < 0x20) {
                throw new Unreadable("a control character in a string at " + position);
            } else {
                out.append(c);
            }
        }
        throw new Unreadable("an unterminated string");
    }

    private Object number() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String number = text.substring(start, position);
        if (number.isEmpty()) {
            throw new Unreadable("unexpected character '" + text.charAt(start) + "' at " + start);
        }
        try {
            BigDecimal value = new BigDecimal(number);
            if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                try {
                    return value.longValueExact();
                } catch (ArithmeticException tooLarge) {
                    return value;
                }
            }
            return value;
        } catch (NumberFormatException broken) {
            throw new Unreadable("no number <" + number + "> at " + start);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw new Unreadable("unexpected text at " + position);
        }
        position += word.length();
        return value;
    }

    private boolean peek(char c) {
        return position < text.length() && text.charAt(position) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new Unreadable("expected '" + c + "' at " + position);
        }
        position++;
    }

    private void skipWhitespace() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }
}
