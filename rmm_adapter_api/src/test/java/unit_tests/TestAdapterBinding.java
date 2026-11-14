package unit_tests;

import com.rmm.adapter.api.AdapterBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The binding carries only its opaque, non-blank identifier. */
class TestAdapterBinding {

    @Test
    void theBindingNeedsAnIdentifier() {
        assertEquals("b-1", new AdapterBinding("b-1").bindingId());
        assertThrows(IllegalArgumentException.class, () -> new AdapterBinding(null));
        assertThrows(IllegalArgumentException.class, () -> new AdapterBinding(" "));
    }
}
