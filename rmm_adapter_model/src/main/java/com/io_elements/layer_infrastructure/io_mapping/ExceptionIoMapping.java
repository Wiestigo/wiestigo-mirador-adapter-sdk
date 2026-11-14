package com.io_elements.layer_infrastructure.io_mapping;

/** Failure while loading or filling an {@link IoMapping}. */
public class ExceptionIoMapping extends Exception {

    public ExceptionIoMapping(String message) {
        super(message);
    }

    public ExceptionIoMapping(String message, Throwable cause) {
        super(message, cause);
    }
}
