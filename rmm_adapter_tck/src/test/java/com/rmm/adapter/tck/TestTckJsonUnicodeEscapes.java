package com.rmm.adapter.tck;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Unicode escape in a document of the TCK's stores is exactly four hexadecimal digits, as in RFC 8259. A
 * sign or a digit of another script is not one, although {@code Integer.parseInt(text, 16)} would take both.
 */
public class TestTckJsonUnicodeEscapes {

    @Test
    public void fourHexadecimalDigitsAreOneCharacter() {
        assertEquals(Map.of("text", "A\u00e9\u20ac\u0000"),
                TckJson.object("{\"text\":\"\\u0041\\u00e9\\u20AC\\u0000\"}"));
    }

    @Test
    public void anythingElseAfterTheEscapeIsNotValidJson() {
        for (String invalid : List.of("\\u+041", "\\u-041", "\\u 041", "\\u004g", "\\uzzzz",
                "\\u\uff10\uff10\uff14\uff11")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> TckJson.object("{\"text\":\"" + invalid + "\"}"), invalid);

            assertTrue(refused.getMessage().contains("invalid unicode escape"), refused.getMessage());
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TckJson.object("{\"text\":\"\\u00"))
                .getMessage().contains("short unicode escape"));
    }
}
