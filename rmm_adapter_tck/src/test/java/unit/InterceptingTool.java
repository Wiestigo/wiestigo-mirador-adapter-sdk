package unit;

import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

/**
 * Wraps a tool so that a test steps into the hooks of the readers and writers its bindings return: every call
 * of such a hook goes to an {@link Interception}, which changes the arguments or the result or records the
 * call. Everything else reaches the tool unchanged.
 */
final class InterceptingTool {

    /** What a test does with one hook call; {@link #forward} passes the call on to {@code port}. */
    interface Interception {
        Object call(Object port, Method hook, Object[] parameters) throws Throwable;
    }

    private InterceptingTool() {
    }

    /** {@code tool} with every hook of its writers going through {@code writing}; its readers stay as they are. */
    static ToolAdapter ofWriterHooks(ToolAdapter tool, Interception writing) {
        return of(tool, null, writing);
    }

    /**
     * {@code tool} with every hook of its readers going through {@code reading} and every hook of its writers
     * going through {@code writing}.
     */
    static ToolAdapter ofReaderAndWriterHooks(ToolAdapter tool, Interception reading, Interception writing) {
        return of(tool, reading, writing);
    }

    /** Calls {@code method} on {@code target} and rethrows the target's exception unwrapped. */
    static Object forward(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    private static ToolAdapter of(ToolAdapter tool, Interception reading, Interception writing) {
        return (ToolAdapter) Proxy.newProxyInstance(ToolAdapter.class.getClassLoader(),
                new Class<?>[]{ToolAdapter.class}, (proxy, method, arguments) -> {
                    Object result = forward(tool, method, arguments);
                    if (!method.getName().equals("bind")) {
                        return result;
                    }
                    AdapterInstance instance = (AdapterInstance) result;
                    return Proxy.newProxyInstance(AdapterInstance.class.getClassLoader(),
                            new Class<?>[]{AdapterInstance.class}, (bound, call, values) -> {
                                Object found = forward(instance, call, values);
                                if (reading != null && call.getName().equals("reader")) {
                                    return intercepted(SourceReader.class, found, reading);
                                }
                                if (call.getName().equals("writer")) {
                                    return intercepted(TargetWriter.class, found, writing);
                                }
                                return found;
                            });
                });
    }

    /** The port in {@code found} (the {@code Optional} that the instance returned) behind {@code interception}. */
    private static <T> Optional<Object> intercepted(Class<T> type, Object found, Interception interception) {
        T port = ((Optional<?>) found).map(type::cast).orElseThrow();
        return Optional.of(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, hook, parameters) -> interception.call(port, hook, parameters)));
    }
}
