package unit_tests;

import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigScope;
import com.rmm.adapter.api.ConfigType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scope of a configuration key: without a scope,
 * {@link ConfigScope#BINDING} applies, for the additional constructor with
 * four arguments as well as for the factories {@code required} and
 * {@code optional}. Only {@code platform(...)} yields
 * {@link ConfigScope#PLATFORM}.
 */
class TestConfigKeyScope {

    @Test
    void fourArgumentsYieldTheBindingScope() {
        ConfigKey key = new ConfigKey("home.url", ConfigType.URL, null, "Address");

        assertEquals(ConfigScope.BINDING, key.scope());
        assertEquals(new ConfigKey("home.url", ConfigType.URL, null, "Address", ConfigScope.BINDING), key);
    }

    @Test
    void theFactoriesYieldTheBindingScope() {
        assertEquals(ConfigScope.BINDING, ConfigKey.required("token", ConfigType.SECRET, "Token").scope());
        assertEquals(ConfigScope.BINDING,
                ConfigKey.optional("page.size", ConfigType.INTEGER, "50", "Page size").scope());
    }

    @Test
    void platformYieldsThePlatformScope() {
        ConfigKey optional = ConfigKey.platform("proxy", ConfigType.STRING, "", "Proxy of the operator");
        ConfigKey required = ConfigKey.platform("ca.bundle", ConfigType.PATH, null, "CA bundle");

        assertEquals(ConfigScope.PLATFORM, optional.scope());
        assertFalse(optional.isRequired());
        assertEquals(ConfigScope.PLATFORM, required.scope());
        assertTrue(required.isRequired());
    }

    @Test
    void aMissingScopeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigKey("home.url", ConfigType.URL, null, "Address", null));
    }

    @Test
    void pathInTheBindingScopeIsRefusedOnlyByTheHost() {
        //The constructor allows PATH/BINDING: otherwise a descriptor would already fail at construction
        ConfigKey key = ConfigKey.optional("mapping.dir", ConfigType.PATH, "/tmp", "Directory");
        assertEquals(ConfigScope.BINDING, key.scope());
    }
}
