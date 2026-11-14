package com.rmm.remote.tck;

/**
 * A violation of remote protocol version 4 found by the TCK for remote
 * services. It is an {@link AssertionError}, so every test framework reports
 * it as a failure. The message names the violation, the case and, where
 * possible, the section of {@code docs/REMOTE_PROTOCOL.md}.
 */
public class ServiceContractViolation extends AssertionError {

    public ServiceContractViolation(String message) {
        super(message);
    }
}
