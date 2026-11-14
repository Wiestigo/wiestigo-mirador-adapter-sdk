package unit_tests;

import com.rmm.adapter.api.SyncRunContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deadline in the run context: the additional constructor with two
 * arguments keeps existing callers unchanged and yields a run without a
 * deadline; the checks of run and project apply to both forms.
 */
class TestSyncRunContextDeadline {

    @Test
    void twoArgumentsYieldARunWithoutDeadline() {
        UUID runId = UUID.randomUUID();
        SyncRunContext context = new SyncRunContext(runId, "PRJ-1");

        assertEquals(runId, context.runId());
        assertEquals("PRJ-1", context.externalProjectId());
        assertNull(context.deadline());
        assertEquals(new SyncRunContext(runId, "PRJ-1", null), context);
    }

    @Test
    void theDeadlineIsCarried() {
        Instant deadline = Instant.parse("2026-10-15T12:00:00Z");
        SyncRunContext context = new SyncRunContext(UUID.randomUUID(), "PRJ-1", deadline);

        assertEquals(deadline, context.deadline());
    }

    @Test
    void onlyASetDeadlineExpiresFromItsInstantOn() {
        Instant deadline = Instant.parse("2026-10-15T12:00:00Z");
        SyncRunContext withDeadline = new SyncRunContext(UUID.randomUUID(), "PRJ-1", deadline);

        assertFalse(withDeadline.expired(deadline.minusMillis(1)));
        assertTrue(withDeadline.expired(deadline));
        assertTrue(withDeadline.expired(deadline.plusSeconds(1)));
        assertFalse(new SyncRunContext(UUID.randomUUID(), "PRJ-1").expired(Instant.MAX), "never without a deadline");
    }

    @Test
    void theChecksApplyToBothConstructors() {
        assertThrows(IllegalArgumentException.class, () -> new SyncRunContext(null, "PRJ-1"));
        assertThrows(IllegalArgumentException.class, () -> new SyncRunContext(UUID.randomUUID(), " "));
        assertThrows(IllegalArgumentException.class,
                () -> new SyncRunContext(null, "PRJ-1", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new SyncRunContext(UUID.randomUUID(), null, Instant.now()));
    }
}
