package com.rmm.adapter.api;

/**
 * Failure to bind the adapter ({@link ToolAdapter#bind}). The host then lists
 * the binding as defective with this cause and starts anyway. The message
 * should tell an administrator what to do without looking at code (wrong URL,
 * missing token, tool unreachable, ...).
 *
 * <p><b>The reason</b> ({@link #reason()}, since contract 1.1) lets a plugin
 * report a state that only the host can name, without knowing a host class:
 * for example a remote service with a protocol that is too old
 * ({@link Reason#PROTOCOL_TOO_OLD}). The constructors without a reason carry
 * {@link Reason#UNSPECIFIED}.
 */
public class AdapterInitialisationException extends AdapterException {

    /** Why binding failed, as far as the host tracks it separately. */
    public enum Reason {
        /** No specific reason: the host lists the failure as a failed bind. */
        UNSPECIFIED,
        /** The remote peer speaks a protocol older than the host requires (below version 4). */
        PROTOCOL_TOO_OLD
    }

    private final Reason reason;

    public AdapterInitialisationException(String message) {
        super(message);
        this.reason = Reason.UNSPECIFIED;
    }

    public AdapterInitialisationException(String message, Throwable cause) {
        super(message, cause);
        this.reason = Reason.UNSPECIFIED;
    }

    /**
     * @param reason the reason; never {@code null}
     * @param cause  the cause; {@code null}: none
     */
    public AdapterInitialisationException(String message, Reason reason, Throwable cause) {
        super(message, cause);
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null — use UNSPECIFIED without a specific reason");
        }
        this.reason = reason;
    }

    /** The reason of the failure; {@link Reason#UNSPECIFIED} if there is no specific one. */
    public Reason reason() {
        return reason;
    }
}
