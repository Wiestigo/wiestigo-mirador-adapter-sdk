package unit_tests;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.ResponseTooLargeException;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP status of an {@link AdapterException}: only valid statuses (100
 * to 599) travel, everything else means "none known"; the constructors
 * without a status carry none.
 */
class TestAdapterException {

    @Test
    void onlyValidStatusesTravel() {
        assertEquals(OptionalInt.of(100), new AdapterException("m", null, false, 100).httpStatus());
        assertEquals(OptionalInt.of(599), new AdapterException("m", null, false, 599).httpStatus());
        assertEquals(OptionalInt.of(401), new AdapterException("m", null, false, 401).httpStatus());
        for (int invalid : new int[]{0, 99, 600, -1}) {
            assertEquals(OptionalInt.empty(), new AdapterException("m", null, true, invalid).httpStatus(),
                    "status " + invalid);
        }
    }

    @Test
    void theEarlierConstructorsCarryNoStatus() {
        IllegalStateException cause = new IllegalStateException("x");
        AdapterException three = new AdapterException("m", cause, true);
        assertTrue(three.httpStatus().isEmpty());
        assertTrue(three.isRetryable());
        assertSame(cause, three.getCause());
        assertTrue(new AdapterException("m", cause).httpStatus().isEmpty());
        assertFalse(new AdapterException("m").isRetryable());
        assertTrue(new AdapterException("m").httpStatus().isEmpty());
    }

    /** A buffered response that is too large has its own type: final, without status, without cause. */
    @Test
    void aResponseTooLargeIsFinalAndWithoutStatus() {
        AdapterException tooLarge = new ResponseTooLargeException("GET https://tool.test/items: too large");
        assertFalse(tooLarge.isRetryable());
        assertTrue(tooLarge.httpStatus().isEmpty());
        assertEquals(null, tooLarge.getCause());
        assertEquals("GET https://tool.test/items: too large", tooLarge.getMessage());
    }
}
