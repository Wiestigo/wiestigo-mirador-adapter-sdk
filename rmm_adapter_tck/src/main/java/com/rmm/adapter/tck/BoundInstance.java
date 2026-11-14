package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.IdentifierKind;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.TargetWriter;

import java.util.List;
import java.util.Optional;

/**
 * What a bound instance declares and provides, read the way the host reads
 * it for a connection: its own capabilities and identifier kinds before
 * those of the descriptor, and the ports of the directions a case works in.
 */
final class BoundInstance {

    private BoundInstance() {
    }

    /**
     * The capabilities against which a case checks a binding: those of the
     * instance if it declares its own ({@link AdapterInstance#capabilities()},
     * for example a remote service per binding), otherwise those of the
     * descriptor. The host does the same for each connection. {@code null}
     * instead of an {@code Optional} is a violation.
     */
    static AdapterCapabilities capabilitiesOf(AdapterDescriptor descriptor, AdapterInstance instance) {
        Optional<AdapterCapabilities> own = instance.capabilities();
        if (own == null) {
            throw new TckViolation("capabilities() of the bound instance returned null instead of an Optional —"
                    + " the host holds such a connection DEFECT INSTANCE_INCONSISTENT; return Optional.empty() for"
                    + " the capabilities of the descriptor");
        }
        return own.orElse(descriptor.capabilities());
    }

    /**
     * The identifier kinds of the binding: those of the instance if it
     * declares its own, otherwise those of the descriptor, as the host takes
     * them for the reference rules of the connection.
     */
    static List<IdentifierKind> identifierKindsOf(AdapterDescriptor descriptor, AdapterInstance instance) {
        Optional<List<IdentifierKind>> own = instance.identifierKinds();
        if (own == null || own.isPresent() && own.get() == null) {
            throw new TckViolation("identifierKinds() of the bound instance returned null instead of an Optional of"
                    + " a list — the host holds such a connection DEFECT INSTANCE_INCONSISTENT");
        }
        return own.orElse(descriptor.identifierKinds());
    }

    /**
     * The reader of a binding in a case that reads from the tool because the
     * descriptor declares the direction {@code TO_RMM}. A binding without a
     * reader is a violation.
     */
    static SourceReader readerOf(AdapterInstance instance) {
        return readerOf(instance, "the direction " + SyncDirection.TO_RMM);
    }

    /** As {@link #readerOf(AdapterInstance)}, where the descriptor declares {@code declared} instead. */
    static SourceReader readerOf(AdapterInstance instance, String declared) {
        return instance.reader().orElseThrow(() -> missingPort(declared, "SourceReader", "reads from"));
    }

    /**
     * The writer of a binding in a case that writes to the tool because the
     * descriptor declares the direction {@code FROM_RMM}. A binding without
     * a writer is a violation.
     */
    static TargetWriter writerOf(AdapterInstance instance) {
        return instance.writer().orElseThrow(() -> missingPort("the direction " + SyncDirection.FROM_RMM,
                "TargetWriter", "writes to"));
    }

    private static TckViolation missingPort(String declared, String port, String use) {
        return new TckViolation("The descriptor declares " + declared + ", but the bound instance provides no "
                + port + " — this case " + use + " the tool through it");
    }
}
