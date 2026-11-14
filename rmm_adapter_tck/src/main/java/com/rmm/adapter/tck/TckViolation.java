package com.rmm.adapter.tck;

/**
 * A violation of the adapter contract found by the TCK. It is an
 * {@link AssertionError} so that every test framework reports it as a
 * failure. The message names the violation and, where possible, the fix.
 */
public class TckViolation extends AssertionError {

    public TckViolation(String message) {
        super(message);
    }
}
