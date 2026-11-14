package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ToolAdapter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case 6 (and the class scan of case 3) for a plugin unit that the host loads from its bytes. Its code
 * source is not a file but {@code rmm-plugin:<sha256>!/}: the TCK reads the entries relative to that URL
 * and the classes from the jar that the code source itself serves.
 *
 * <p>The TCK does not know the host, so the test reproduces this with its own handler for the scheme: a
 * class defined with that code source, and the entries of a jar behind the URL.</p>
 */
public class TestPluginUnitOverAUrl {

    private static final String ROOT = "rmm-plugin:" + "ab".repeat(32) + "!/";
    private static final String MANIFEST = "{ \"typeKey\": \"URLUNIT\" }";

    @Test
    public void theEntriesOfAUnitThatIsOnlyAUrlAreReadRelativeToIt() throws Exception {
        Class<? extends ToolAdapter> adapter = definedInTheUnit();

        PluginUnit unit = PluginUnit.of(adapter);

        assertEquals(ROOT, unit.toString());
        assertTrue(unit.declaresAsService(adapter), "the services file of the unit names the adapter");
        assertEquals(Optional.of(MANIFEST), unit.entry("META-INF/rmm-adapter.json"));
        assertEquals(Optional.empty(), unit.entry("META-INF/rmm-adapter/mapping-template.json"));
    }

    @Test
    public void theClassesOfAUnitThatIsOnlyAUrlAreTheClassesOfItsJar() throws Exception {
        Class<? extends ToolAdapter> adapter = definedInTheUnit();

        Set<Class<?>> classes = ProcessWideState.classesOf(adapter);

        assertEquals(Set.of(UrlUnitAdapter.class.getName()),
                Set.copyOf(classes.stream().map(Class::getName).toList()));
        assertFalse(classes.isEmpty());
    }

    // ------------------------------------------------------------------

    /** The adapter as the unit carries it; abstract is enough because case 6 creates no instance. */
    abstract static class UrlUnitAdapter implements ToolAdapter {
        @Override
        public abstract AdapterDescriptor descriptor();

        @Override
        public abstract AdapterInstance bind(AdapterContext context);
    }

    /** {@link UrlUnitAdapter}, defined a second time with {@value #ROOT} as its code source. */
    @SuppressWarnings("unchecked")
    private static Class<? extends ToolAdapter> definedInTheUnit() throws IOException {
        String classFile = UrlUnitAdapter.class.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = TestPluginUnitOverAUrl.class.getClassLoader().getResourceAsStream(classFile)) {
            bytes = in.readAllBytes();
        }
        Map<String, byte[]> entries = Map.of(
                "META-INF/services/" + ToolAdapter.class.getName(),
                (UrlUnitAdapter.class.getName() + "\n").getBytes(StandardCharsets.UTF_8),
                "META-INF/rmm-adapter.json", MANIFEST.getBytes(StandardCharsets.UTF_8),
                classFile, bytes);
        URL root = new URL(null, ROOT, new UnitHandler(entries, jarOf(entries)));
        return (Class<? extends ToolAdapter>) new UnitLoader().define(UrlUnitAdapter.class.getName(), bytes, root);
    }

    private static byte[] jarOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Defines a class with a code source that is not a file. */
    private static final class UnitLoader extends SecureClassLoader {
        UnitLoader() {
            super(TestPluginUnitOverAUrl.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes, URL location) {
            return defineClass(name, bytes, 0, bytes.length, new CodeSource(location,
                    (java.security.cert.Certificate[]) null));
        }
    }

    /** The unit's scheme as the host serves it: the root yields the jar, a path yields its entry. */
    private static final class UnitHandler extends URLStreamHandler {
        private final Map<String, byte[]> entries;
        private final byte[] jar;

        UnitHandler(Map<String, byte[]> entries, byte[] jar) {
            this.entries = entries;
            this.jar = jar;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            String path = url.getFile().substring(url.getFile().indexOf("!/") + 2);
            return new URLConnection(url) {
                @Override
                public void connect() {
                    connected = true;
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    if (path.isEmpty()) {
                        return new ByteArrayInputStream(jar);
                    }
                    byte[] content = entries.get(path);
                    if (content == null) {
                        throw new FileNotFoundException(url.toString());
                    }
                    return new ByteArrayInputStream(content);
                }
            };
        }
    }
}
