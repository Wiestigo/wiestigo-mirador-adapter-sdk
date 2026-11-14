package unit;

import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.ContractContext;
import com.rmm.adapter.tck.ContractHost;
import com.rmm.adapter.tck.TckViolation;
import com.rmm.adapter.tck.reference.InMemoryToolAdapter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shows that an adapter's suite cannot swap the {@link ContractHost}: if it overrides
 * {@code contractHost()}, every case fails, even when its host only passes the TCK's context on while
 * making case 4 hollow. The default host runs normally.
 */
public class TestContractHostGuard {

    /** A suite outside the packages of the host and the TCK. */
    private static final class Suite extends AdapterContractTest {

        private final ContractHost host;

        private Suite(ContractHost host) {
            this.host = host;
        }

        @Override
        protected ToolAdapter adapter() {
            return new InMemoryToolAdapter();
        }

        @Override
        protected ContractHost contractHost() {
            return host;
        }
    }

    @Test
    public void aSuiteOfAnAdapterCannotReplaceTheContext() {
        ContractHost lenient = new ContractHost() {
            @Override
            public ContractContext context(AdapterDescriptor descriptor, Map<String, String> configuration,
                                           Map<String, String> platform) {
                return ContractHost.tck().context(descriptor, configuration, platform);
            }

            /** Makes case 4 hollow: no thread counts. */
            @Override
            public boolean ownsThread(Thread thread) {
                return true;
            }
        };

        TckViolation violation = assertThrows(TckViolation.class,
                new Suite(lenient)::theCasesRunAgainstTheTckOrTheHostOfTheRmm);
        assertTrue(violation.getMessage().contains("keeps the default ContractHost.tck()"), violation.getMessage());
    }

    @Test
    public void theDefaultHostRunsAsBefore() {
        assertDoesNotThrow(new Suite(ContractHost.tck())::theCasesRunAgainstTheTckOrTheHostOfTheRmm);
    }
}
