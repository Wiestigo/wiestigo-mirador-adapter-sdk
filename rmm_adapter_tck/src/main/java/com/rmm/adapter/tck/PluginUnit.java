package com.rmm.adapter.tck;

import com.rmm.adapter.api.ToolAdapter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The plugin unit an adapter is loaded from (TCK case 6): the code source of
 * its class, a jar or, during a build, the classes directory. The unit is a
 * plugin if it names the adapter for the {@code ServiceLoader}
 * ({@value #SERVICE_FILE}); that is how the host finds it. If the host loads
 * the unit from its bytes (code source {@code rmm-plugin:<sha256>!/}), the
 * case reads the unit's entries through that URL. An adapter type that is
 * part of the application's own code (such as the TCK's reference adapters)
 * is not a plugin.
 */
final class PluginUnit {

    /** The file through which the host's {@code ServiceLoader} finds the adapter of a unit. */
    static final String SERVICE_FILE = "META-INF/services/" + "com.rmm.adapter.api.ToolAdapter";

    /** The unit as a file or directory; {@code null} if it can only be read as a URL. */
    private final Path location;
    /** The code source as a URL (for example the host's {@code rmm-plugin:<sha256>!/}). */
    private final URL url;

    private PluginUnit(Path location, URL url) {
        this.location = location;
        this.url = url;
    }

    /**
     * The unit of the class.
     *
     * @throws TckViolation if its code source cannot be determined
     */
    static PluginUnit of(Class<? extends ToolAdapter> adapterClass) {
        URL source = codeSourceOf(adapterClass);
        if (source == null) {
            throw new TckViolation("Case 6: the code source of " + adapterClass.getName()
                    + " cannot be determined — the plugin unit (jar) cannot be checked");
        }
        if (!"file".equalsIgnoreCase(source.getProtocol())) {
            //A unit the host loads from bytes (rmm-plugin:<sha256>!/): read through its URL, with entries
            //relative to it
            return new PluginUnit(null, source);
        }
        try {
            return new PluginUnit(Path.of(source.toURI()), source);
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            throw new TckViolation("Case 6: the code source " + source + " of "
                    + adapterClass.getName() + " cannot be read (" + e + ")");
        }
    }

    /**
     * Where a class was loaded from: the location of its code source (a jar, a classes directory or the URL
     * of a unit the host loads from bytes); {@code null} if it cannot be determined.
     */
    static URL codeSourceOf(Class<?> type) {
        CodeSource source = type.getProtectionDomain() == null ? null : type.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
    }

    /** Whether the unit names this adapter for the {@code ServiceLoader}, that is, whether it is a plugin. */
    boolean declaresAsService(Class<?> adapterClass) {
        return entry(SERVICE_FILE).map(services -> services.lines()
                        .map(line -> line.replaceAll("#.*", "").trim())
                        .anyMatch(adapterClass.getName()::equals))
                .orElse(false);
    }

    /**
     * The content of an entry of the unit (UTF-8); empty if the unit has no
     * such entry. Only the unit itself is read, never the class path: an
     * entry of another jar does not count, as in the host, which reads only
     * the plugin's jar.
     */
    Optional<String> entry(String name) {
        try {
            if (location == null) {
                return entryOverUrl(name);
            }
            if (Files.isDirectory(location)) {
                Path file = location.resolve(name);
                return Files.isRegularFile(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                        : Optional.empty();
            }
            try (JarFile jar = new JarFile(location.toFile())) {
                JarEntry entry = jar.getJarEntry(name);
                if (entry == null) {
                    return Optional.empty();
                }
                try (InputStream content = jar.getInputStream(entry)) {
                    return Optional.of(new String(content.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new TckViolation("Case 6: " + name + " of the plugin unit " + this + " cannot be read ("
                    + e.getMessage() + ")");
        }
    }

    /** An entry of a unit that can only be read as a URL; empty if the unit has no such entry. */
    private Optional<String> entryOverUrl(String name) throws IOException {
        URLConnection connection = new URL(url, name).openConnection();
        connection.setUseCaches(false);
        try (InputStream content = connection.getInputStream()) {
            return Optional.of(new String(content.readAllBytes(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException | java.nio.file.NoSuchFileException missing) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return location == null ? url.toString() : location.toString();
    }
}
