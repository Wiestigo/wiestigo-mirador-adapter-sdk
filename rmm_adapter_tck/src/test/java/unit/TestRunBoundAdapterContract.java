package unit;

import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInitialisationException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.MetadataProvider;
import com.rmm.adapter.api.PathHierarchy;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.ProcessWideState;
import com.rmm.adapter.tck.reference.InMemoryToolAdapter;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shows that a correct adapter that uses {@code runStore()} and {@code executor().submit} in every read and
 * write hook, as {@code rmm_adapter_template} recommends, passes every case of the TCK, including all live
 * cases. The TCK calls every hook inside a run of the connection, as the host does; a call outside a run
 * would fail here with {@link IllegalStateException}.
 *
 * <p>The adapter is the in-memory reference ({@link InMemoryToolAdapter}) wrapped in {@link RunBound}.
 * Before every hook the wrapper puts a document into the run store and asks for the run identifier in a
 * task of the run; it must equal the run of the hook.</p>
 */
public class TestRunBoundAdapterContract extends TestInMemoryAdapterContract {

    @Override
    protected ToolAdapter adapter() {
        return new RunBound(super.adapter());
    }

    @Override
    protected Set<Class<?>> adapterClasses() {
        Set<Class<?>> classes = new HashSet<>(ProcessWideState.classesOf(InMemoryToolAdapter.class));
        classes.addAll(Set.of(RunBound.class, Instance.class, InRun.class));
        return classes;
    }

    /** The wrapper of the type: binds the reference and wraps the reader and writer of the binding. */
    static final class RunBound implements ToolAdapter {

        private final ToolAdapter delegate;

        RunBound(ToolAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public AdapterDescriptor descriptor() {
            return delegate.descriptor();
        }

        @Override
        public AdapterInstance bind(AdapterContext context) throws AdapterInitialisationException {
            return new Instance(delegate.bind(context), context);
        }
    }

    /** A binding: reader and writer go through {@link InRun}, everything else is delegated unchanged. */
    static final class Instance implements AdapterInstance {

        private final AdapterInstance delegate;
        private final AdapterContext context;
        private final AtomicLong calls = new AtomicLong();

        Instance(AdapterInstance delegate, AdapterContext context) {
            this.delegate = delegate;
            this.context = context;
        }

        @Override
        public Optional<SourceReader> reader() {
            return delegate.reader().map(reader -> inRun(SourceReader.class, reader));
        }

        @Override
        public Optional<TargetWriter> writer() {
            return delegate.writer().map(writer -> inRun(TargetWriter.class, writer));
        }

        @Override
        public Optional<MetadataProvider> metadata() {
            return delegate.metadata();
        }

        @Override
        public Optional<PathHierarchy> pathHierarchy() {
            return delegate.pathHierarchy();
        }

        @Override
        public void checkHealth() throws AdapterException {
            delegate.checkHealth();
        }

        @Override
        public Optional<AdapterCapabilities> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public List<ConfigKey> connectionKeys() {
            return delegate.connectionKeys();
        }

        @Override
        public Optional<List<com.rmm.adapter.api.IdentifierKind>> identifierKinds() {
            return delegate.identifierKinds();
        }

        @Override
        public void purge() throws AdapterException {
            delegate.purge();
        }

        @Override
        public Optional<ReadScope> parseEvent(Map<String, List<String>> headers, InputStream body)
                throws AdapterException {
            return delegate.parseEvent(headers, body);
        }

        @Override
        public void close() {
            delegate.close();
        }

        private <T> T inRun(Class<T> port, T target) {
            return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port},
                    new InRun(target, context, calls)));
        }
    }

    /** Before every hook: one document in {@code runStore()} and one task from {@code executor().submit}. */
    static final class InRun implements InvocationHandler {

        private final Object target;
        private final AdapterContext context;
        private final AtomicLong calls;

        InRun(Object target, AdapterContext context, AtomicLong calls) {
            this.target = target;
            this.context = context;
            this.calls = calls;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "InRun[" + target + "]";
                };
            }
            UUID run = context.runStore().runId();
            context.runStore().collection("hooks").put(method.getName() + "-" + calls.incrementAndGet(),
                    "{\"hook\":\"" + method.getName() + "\"}");
            UUID ofTheTask;
            try {
                ofTheTask = context.executor().submit(() -> context.runStore().runId()).get();
            } catch (ExecutionException failed) {
                throw failed.getCause();
            }
            if (!run.equals(ofTheTask)) {
                throw new IllegalStateException("a task of the run named another run: " + ofTheTask + " in " + run);
            }
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failed) {
                throw failed.getCause();
            }
        }
    }
}
