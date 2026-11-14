package com.io_elements.layer_infrastructure.io_mapping;

/**
 * Loads a complete {@link IoMapping} from a data source.
 *
 * <p>Implementations can connect different sources (file, database, remote
 * service); the type parameter describes the source reference (for example
 * {@code Path} for the {@link IoMappingJsonReader}).</p>
 */
public interface IoMappingReader<T> {

    /** Loads the complete mapping (both directions) from the source. */
    IoMapping read(T source) throws ExceptionIoMapping;

    /** Whether the mapping source is available. */
    boolean isAvailable(T source);

    /** Description of the source for logs and diagnostics (for example "File: mapping.json"). */
    String getSourceDescription(T source);
}
