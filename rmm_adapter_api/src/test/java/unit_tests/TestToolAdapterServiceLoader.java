package unit_tests;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.ToolAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the host discovers plugins: an adapter is found only through
 * META-INF/services, exactly as the host loads customer jars. The test is
 * therefore also the executable documentation of the registration format.
 */
class TestToolAdapterServiceLoader {

    @Test
    void serviceLoaderFindsTheDeclaredAdapter() {
        List<ToolAdapter> adapters = ServiceLoader.load(ToolAdapter.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        assertEquals(1, adapters.size(), "exactly the one declared adapter");
        AdapterDescriptor descriptor = adapters.get(0).descriptor();
        assertEquals("dummy", descriptor.systemKey());
        assertEquals(AdapterApiVersion.MAJOR, descriptor.apiMajorVersion());
    }

    @Test
    void theContractIsVersion1() {
        //Contract major 1: bind() once per binding; the host does not load major 0
        assertEquals(1, AdapterApiVersion.MAJOR);
    }

    @Test
    void aBoundAdapterMeetsTheConsistencyRuleOfTheDirections() throws Exception {
        ToolAdapter adapter = ServiceLoader.load(ToolAdapter.class).findFirst().orElseThrow();
        AdapterInstance instance = adapter.bind(null);

        //The rule the host checks after binding:
        //a declared direction requires the matching port.
        boolean toRmm = adapter.descriptor().capabilities().directions().contains(SyncDirection.TO_RMM);
        boolean fromRmm = adapter.descriptor().capabilities().directions().contains(SyncDirection.FROM_RMM);
        assertTrue(!toRmm || instance.reader().isPresent(), "TO_RMM requires a SourceReader");
        assertTrue(!fromRmm || instance.writer().isPresent(), "FROM_RMM requires a TargetWriter");
    }
}
