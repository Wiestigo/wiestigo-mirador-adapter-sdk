package com.rmm.adapter.tck;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * Test access to the clock of the TCK's TLS material ({@link TckTlsMaterial#clock}) for tests outside the
 * package. Any material built while the access is open asks {@code now} whether the client identity has
 * expired; {@link #close()} puts back the clock that was set before. Open it in a {@code try} with resources,
 * so that a failing test does not leave its clock to the tests that follow.
 */
public final class TckTlsClockTestAccess implements AutoCloseable {

    /** The clock that {@link #use} replaced. */
    private final Clock previous;

    private TckTlsClockTestAccess(Clock previous) {
        this.previous = previous;
    }

    /** From now on, every new material is built with the clock {@code now}. */
    public static TckTlsClockTestAccess use(Supplier<Instant> now) {
        Clock previous = TckTlsMaterial.clock;
        TckTlsMaterial.clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        return new TckTlsClockTestAccess(previous);
    }

    @Override
    public void close() {
        TckTlsMaterial.clock = previous;
    }
}
