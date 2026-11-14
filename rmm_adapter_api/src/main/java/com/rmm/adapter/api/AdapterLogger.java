package com.rmm.adapter.api;

/**
 * Logging facade of the adapter: writes to the host log, tagged with the system
 * key. Deliberately a facade of its own instead of a logging library in the
 * contract: the adapter stays free of framework dependencies, and the host
 * decides on format and sink.
 */
public interface AdapterLogger {

    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable cause);
}
