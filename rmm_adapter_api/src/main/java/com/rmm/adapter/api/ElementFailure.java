package com.rmm.adapter.api;

/**
 * A single element that could not be converted while reading. It is reported
 * instead of aborting the page (error isolation per element).
 *
 * @param externalId ID of the element in the tool; null if not even the ID
 *                   could be read.
 * @param message    human-readable cause; appears in the run result.
 * @param cause      original exception, if any (for logs).
 */
public record ElementFailure(String externalId, String message, Throwable cause) {

    public ElementFailure {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be empty");
        }
    }

    public static ElementFailure of(String externalId, String message) {
        return new ElementFailure(externalId, message, null);
    }
}
