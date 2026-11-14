package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterExecutor;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.tck.AdapterExecutorSemantics;
import com.rmm.adapter.tck.TckAdapterContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Runs the semantics of {@code executor()} ({@link AdapterExecutorSemantics}) against the TCK's executor.
 * The host runs the same checks against its own executor, so what an adapter passes in the TCK holds in the
 * host as well, including nested tasks, which must not deadlock the pool.
 */
public class TestTckAdapterExecutorSemantics extends AdapterExecutorSemantics {

    private static AdapterDescriptor descriptor() {
        return new AdapterDescriptor("semtool", "Semantics tool", "1.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                        false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null),
                List.of(), List.of());
    }

    @Override
    protected Subject subject(int parallelism) {
        TckAdapterContext context = TckAdapterContext.builder(descriptor()).parallelism(parallelism).build();
        return new Subject() {
            @Override
            public AdapterExecutor executor() {
                return context.executor();
            }

            @Override
            public <T> T inRun(Callable<T> work) throws Exception {
                return context.inRun(new SyncRunContext(UUID.randomUUID(), "SEM-PRJ"), work);
            }

            @Override
            public void close() {
                context.release();
            }
        };
    }
}
