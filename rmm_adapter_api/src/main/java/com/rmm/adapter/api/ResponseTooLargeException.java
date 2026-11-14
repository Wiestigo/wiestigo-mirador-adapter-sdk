package com.rmm.adapter.api;

/**
 * The response was larger than the maximum size of a buffered response
 * ({@link AdapterHttp#send}): final, without a status, and with a message that
 * states the limit. An adapter that reacts with smaller pages (for example by
 * halving the page) recognizes the reason by this type, not by the message
 * text. It reads large content with {@link AdapterHttp#open}, to which the
 * limit does not apply.
 */
public class ResponseTooLargeException extends AdapterException {

    private static final long serialVersionUID = 1L;

    public ResponseTooLargeException(String message) {
        super(message, null, false);
    }
}
