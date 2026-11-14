package unit_tests;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInitialisationException;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Additions of contract 1.1: the value type
 * {@link ConfigType#CLIENT_IDENTITY}, the client identity of the connection,
 * which the host creates and holds, and a reason on
 * {@link AdapterInitialisationException} through which a plugin reports a
 * state that only the host names ({@code PROTOCOL_TOO_OLD}). The
 * constructors without a reason carry {@code UNSPECIFIED}.
 */
class TestClientIdentityContract {

    @Test
    void theClientIdentityIsAValueTypeOfItsOwnAfterTheEarlierOnes() {
        assertEquals(List.of(ConfigType.STRING, ConfigType.INTEGER, ConfigType.BOOLEAN, ConfigType.PATH,
                ConfigType.SECRET, ConfigType.URL, ConfigType.CERTIFICATE, ConfigType.CLIENT_IDENTITY),
                List.of(ConfigType.values()), "an addition at the end: the earlier values keep their position");
        ConfigKey client = ConfigKey.optional("tls.client", ConfigType.CLIENT_IDENTITY, "", "Client identity");
        assertEquals(ConfigType.CLIENT_IDENTITY, client.type());
    }

    @Test
    void anInitialisationFailureNamesItsReasonAndTheEarlierConstructorsNameNone() {
        IllegalStateException cause = new IllegalStateException("x");
        AdapterInitialisationException tooOld = new AdapterInitialisationException(
                "Remote protocol 3 is too old; the host needs version 4",
                AdapterInitialisationException.Reason.PROTOCOL_TOO_OLD, cause);

        assertEquals(AdapterInitialisationException.Reason.PROTOCOL_TOO_OLD, tooOld.reason());
        assertSame(cause, tooOld.getCause());
        assertEquals("Remote protocol 3 is too old; the host needs version 4", tooOld.getMessage());
        assertFalse(tooOld.isRetryable(), "waiting does not make an old protocol newer");
        assertNull(new AdapterInitialisationException("m", AdapterInitialisationException.Reason.PROTOCOL_TOO_OLD,
                null).getCause());
        assertEquals(AdapterInitialisationException.Reason.UNSPECIFIED,
                new AdapterInitialisationException("m").reason());
        assertEquals(AdapterInitialisationException.Reason.UNSPECIFIED,
                new AdapterInitialisationException("m", cause).reason());
        assertEquals(List.of(AdapterInitialisationException.Reason.UNSPECIFIED,
                AdapterInitialisationException.Reason.PROTOCOL_TOO_OLD),
                List.of(AdapterInitialisationException.Reason.values()));
        assertThrows(IllegalArgumentException.class, () -> new AdapterInitialisationException("m", null, cause));
        AdapterException asAdapterFailure = tooOld;
        assertSame(tooOld, asAdapterFailure);
    }
}
