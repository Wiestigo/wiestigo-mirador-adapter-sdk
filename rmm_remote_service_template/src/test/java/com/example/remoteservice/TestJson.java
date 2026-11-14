package com.example.remoteservice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The library-free JSON of the template: reads what the wire carries and writes it back unchanged. */
public class TestJson {

    @Test
    public void aWireElementSurvivesTheRoundTrip() {
        String element = "{\"id\":\"WI-1\",\"fields\":[{\"name\":\"Name\",\"value\":\"a \\\"quoted\\\" \\u00e4\\n\"},"
                + "{\"name\":\"Count\",\"value\":null}],\"orderIndex\":3,\"ratio\":0.5,\"open\":true,\"tags\":[]}";
        Map<String, Object> parsed = Json.parseObject(element);
        assertEquals("a \"quoted\" ä\n", ((Map<?, ?>) ((List<?>) parsed.get("fields")).get(0)).get("value"));
        assertNull(((Map<?, ?>) ((List<?>) parsed.get("fields")).get(1)).get("value"));
        assertEquals(3L, parsed.get("orderIndex"));
        assertEquals(new BigDecimal("0.5"), parsed.get("ratio"));
        assertEquals(parsed, Json.parseObject(Json.write(parsed)));
    }

    @Test
    public void textThatIsNoJsonObjectIsRefused() {
        for (String broken : List.of("", "[]", "{\"a\":}", "{\"a\":1} x", "{\"a\":\"\\q\"}", "{'a':1}",
                "[".repeat(100))) {
            assertThrows(Json.Unreadable.class, () -> Json.parseObject(broken), broken);
        }
    }
}
