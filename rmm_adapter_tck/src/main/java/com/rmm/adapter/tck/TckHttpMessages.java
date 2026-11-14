package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterRequest;

import java.time.Duration;

/** The wording that the messages of the HTTP path share with the host: the request, a duration and a cause. */
final class TckHttpMessages {

    private TckHttpMessages() {
    }

    /** Method and address without query, then the reason; same format as the host. */
    static String message(AdapterRequest request, String reason) {
        return request.method() + " " + AdapterRequest.forLog(request.uri()) + ": " + reason;
    }

    /** Whole seconds as {@code 2 s}, anything else in milliseconds. */
    static String describe(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? millis / 1000 + " s" : millis + " ms";
    }

    /** The simple name of the cause's type and, if it has one, its message. */
    static String describe(Throwable cause) {
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
