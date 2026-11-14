package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests case 6: a plugin unit (a code source that names the adapter under {@code META-INF/services}) without a
 * manifest or without a mapping template fails the TCK, as does a manifest that does not match the descriptor
 * or names a template path the host does not read. An adapter without a {@code ServiceLoader} entry is no
 * plugin; the case is skipped for it, unless the unit carries a manifest (then the case fails). Since contract
 * 1.1 the manifest also names {@code apiMinor} and the image reference forms.
 *
 * <p>The unit is a test directory with the compiled class {@link PluginAdapter}, loaded by a class loader of
 * its own: only then is this directory its code source, not the class directory of the tests.</p>
 */
public class TestPluginUnitContract {

    private static final String SERVICE_FILE = "META-INF/services/" + ToolAdapter.class.getName();

    /** The manifest that matches {@link PluginAdapter}. */
    private static final String MANIFEST = """
            {
              "_comment": "a plugin unit of the TCK self test",
              "typeKey": "UNITTEST",
              "name": "Unit Test Tool",
              "version": "0.3",
              "vendor": "TCK",
              "apiMajor": %d,
              "apiMinor": 0,
              "configKeys": [
                { "key": "home.url", "type": "URL", "scope": "BINDING", "required": true },
                { "key": "page.size", "type": "INTEGER", "scope": "BINDING", "required": false }
              ],
              "capabilities": { "directions": ["TO_RMM"], "hierarchyModel": "FLAT", "imageReferenceForms": [] }
            }
            """.formatted(AdapterApiVersion.MAJOR);

    /** Like {@link #MANIFEST}, built against minor version 1. */
    private static final String MINOR_ONE_MANIFEST = MANIFEST.replace("\"apiMinor\": 0,", "\"apiMinor\": 1,");

    /** The manifest that matches {@link FormsAdapter}: one image reference form, minor version 1. */
    private static final String FORMS_MANIFEST = MINOR_ONE_MANIFEST.replace("\"imageReferenceForms\": []",
            "\"imageReferenceForms\": [ { \"kind\": \"SCHEME\", \"value\": \"attachment\" } ]");

    private static final String TEMPLATE = """
            { "artifactTypeField": "Type", "typeMappings": { "unittestToRmm": { "Story": "Requirement" } } }
            """;

    @TempDir
    Path unit;

    /** An adapter as a plugin brings it: public, with a no-argument constructor. */
    public static final class PluginAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor("UNITTEST", "Unit Test Tool", "0.3", AdapterApiVersion.MAJOR,
                    new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT, false, false, false,
                            false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null),
                    List.of(ConfigKey.required("home.url", ConfigType.URL, "the address"),
                            ConfigKey.optional("page.size", ConfigType.INTEGER, "50", "the page size")),
                    List.of());
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new AdapterInstance() {
                @Override
                public Optional<SourceReader> reader() {
                    return Optional.empty();
                }

                @Override
                public Optional<TargetWriter> writer() {
                    return Optional.empty();
                }
            };
        }
    }

    /** Like {@link PluginAdapter}, with an image reference form ({@code attachment:<file>}), minor version 1. */
    public static final class FormsAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withForms(1);
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Like {@link FormsAdapter}, but with the seven-argument constructor: minor version 0. */
    public static final class FormsAtMinorZeroAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withForms(0);
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Like {@link PluginAdapter} with a client identity, but minor version 0. */
    public static final class ClientIdentityAtMinorZeroAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withKey(ConfigKey.required("tls.client", ConfigType.CLIENT_IDENTITY, "the client identity"));
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Like {@link PluginAdapter} with the connect timeout key, but minor version 0. */
    public static final class ConnectTimeoutAtMinorZeroAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withKey(ConfigKey.optional(com.rmm.adapter.api.AdapterConventions.CONNECT_TIMEOUT_KEY,
                    ConfigType.INTEGER, "10", "the connect timeout"));
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Public because another class loader loads the adapters above (a different runtime package). */
    public static AdapterDescriptor withForms(int minor) {
        AdapterDescriptor plain = new PluginAdapter().descriptor();
        return new AdapterDescriptor(plain.systemKey(), plain.displayName(), plain.adapterVersion(),
                plain.apiMajorVersion(), plain.capabilities().withImageReferenceForms(
                        Set.of(com.rmm.adapter.api.ImageReferenceForm.scheme("attachment"))),
                plain.configKeys(), plain.identifierKinds(), minor);
    }

    /** Like {@link FormsAdapter}, with a scheme that the grammar of the contract allows but the host never accepts. */
    public static final class DottedSchemeAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withScheme("tool.att");
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Like {@link DottedSchemeAdapter}, with {@code +} in the scheme. */
    public static final class PlusSchemeAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withScheme("tool+att");
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Like {@link DottedSchemeAdapter}, with a scheme of a single letter. */
    public static final class OneLetterSchemeAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withScheme("x");
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Like {@link DottedSchemeAdapter}, with a scheme by the rule of the host ({@code -}, digits, 32 characters). */
    public static final class HostSchemeAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withScheme(HOST_SCHEME_OF_32);
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** A scheme by the rule of the host, with the maximum length. */
    public static final String HOST_SCHEME_OF_32 = "tool-att-0123456789-0123456789ab";

    /** Public because another class loader loads the adapters above (a different runtime package). */
    public static AdapterDescriptor withScheme(String scheme) {
        AdapterDescriptor plain = new PluginAdapter().descriptor();
        return new AdapterDescriptor(plain.systemKey(), plain.displayName(), plain.adapterVersion(),
                plain.apiMajorVersion(), plain.capabilities().withImageReferenceForms(
                        Set.of(com.rmm.adapter.api.ImageReferenceForm.scheme(scheme))),
                plain.configKeys(), plain.identifierKinds(), 1);
    }

    /** {@link PluginAdapter} with one more key, minor version 0. */
    public static AdapterDescriptor withKey(ConfigKey key) {
        AdapterDescriptor plain = new PluginAdapter().descriptor();
        List<ConfigKey> keys = new java.util.ArrayList<>(plain.configKeys());
        keys.add(key);
        return new AdapterDescriptor(plain.systemKey(), plain.displayName(), plain.adapterVersion(),
                plain.apiMajorVersion(), plain.capabilities(), keys, plain.identifierKinds());
    }

    /** Like {@link PluginAdapter}, built against the minor version of this contract (eight-argument constructor). */
    public static final class CurrentMinorAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withMinor(AdapterApiVersion.MINOR);
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Like {@link PluginAdapter}, built against a minor version this contract does not know yet. */
    public static final class NewerMinorAdapter implements ToolAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return withMinor(AdapterApiVersion.MINOR + 1);
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            return new PluginAdapter().bind(context);
        }
    }

    /** Public because another class loader loads the adapters above (a different runtime package). */
    public static AdapterDescriptor withMinor(int minor) {
        AdapterDescriptor plain = new PluginAdapter().descriptor();
        return new AdapterDescriptor(plain.systemKey(), plain.displayName(), plain.adapterVersion(),
                plain.apiMajorVersion(), plain.capabilities(), plain.configKeys(), plain.identifierKinds(), minor);
    }

    @Test
    public void aPluginUnitWithManifestAndTemplatePasses() throws Exception {
        write(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", MANIFEST);
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);

        assertDoesNotThrow(() -> caseSixOf(loadFromTheUnit()));
    }

    @Test
    public void aPluginUnitWithoutManifestFailsWithThePath() throws Exception {
        write(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);

        TckViolation violation = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(violation.getMessage().contains("META-INF/rmm-adapter.json"), violation.getMessage());
    }

    @Test
    public void aPluginUnitWithoutMappingTemplateFails() throws Exception {
        write(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", MANIFEST);

        TckViolation violation = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(violation.getMessage().contains("META-INF/rmm-adapter/mapping-template.json")
                && violation.getMessage().contains("No element mapping"), violation.getMessage());
    }

    @Test
    public void aManifestThatDiffersFromTheDescriptorOrNamesATemplatePathFails() throws Exception {
        write(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);
        write("META-INF/rmm-adapter.json", MANIFEST.replace("\"0.3\"", "\"0.4\""));
        TckViolation version = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(version.getMessage().contains("version"), version.getMessage());

        write("META-INF/rmm-adapter.json", MANIFEST.replace("\"required\": false", "\"required\": true"));
        TckViolation keys = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(keys.getMessage().contains("configKeys"), keys.getMessage());

        write("META-INF/rmm-adapter.json", MANIFEST.replace("\"_comment\"",
                "\"mappingTemplate\": \"META-INF/elsewhere.json\", \"_comment\""));
        TckViolation path = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(path.getMessage().contains("mappingTemplate"), path.getMessage());
    }

    @Test
    public void anUnreadableTemplateFails() throws Exception {
        write(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", MANIFEST);
        write("META-INF/rmm-adapter/mapping-template.json", "[ \"no mapping\" ]");

        TckViolation violation = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(violation.getMessage().contains("no mapping the host reads"), violation.getMessage());
    }

    /**
     * The manifest names the minor version of the contract the adapter is built against ({@code apiMinor}),
     * equal to {@link AdapterDescriptor#apiMinorVersion()}. The host reads it before any code runs; without it
     * or with another value case 6 fails.
     */
    @Test
    public void aManifestWithoutApiMinorOrWithAnotherOneFails() throws Exception {
        write(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);

        write("META-INF/rmm-adapter.json", MANIFEST.replace("\"apiMinor\": 0,", ""));
        TckViolation missing = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(missing.getMessage().contains("apiMinor"), missing.getMessage());

        write("META-INF/rmm-adapter.json", MANIFEST.replace("\"apiMinor\": 0,", "\"apiMinor\": 1,"));
        TckViolation other = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(other.getMessage().contains("apiMinor") && other.getMessage().contains("<1>"),
                other.getMessage());

        write("META-INF/rmm-adapter.json", MANIFEST.replace("\"apiMinor\": 0,", "\"apiMinor\": \"0\","));
        TckViolation text = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(text.getMessage().contains("apiMinor"), text.getMessage());
    }

    /**
     * An adapter that calls the seven-argument constructor is built against minor version 0 and passes with
     * {@code "apiMinor": 0}; one built against the current minor version passes with that version. A minor
     * version above that of this contract fails: a host of this version does not load the unit.
     */
    @Test
    public void theMinorVersionOfTheManifestFollowsTheDescriptorUpToThisContract() throws Exception {
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);

        write(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", MANIFEST);
        ToolAdapter sevenArguments = loadFromTheUnit();
        assertEquals(0, sevenArguments.descriptor().apiMinorVersion());
        assertDoesNotThrow(() -> caseSixOf(sevenArguments));

        write(SERVICE_FILE, CurrentMinorAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", MANIFEST.replace("\"apiMinor\": 0,",
                "\"apiMinor\": " + AdapterApiVersion.MINOR + ","));
        assertDoesNotThrow(() -> caseSixOf(loadFromTheUnit(CurrentMinorAdapter.class)));
        write("META-INF/rmm-adapter.json", MANIFEST);
        TckViolation behind = assertThrows(TckViolation.class,
                () -> caseSixOf(loadFromTheUnit(CurrentMinorAdapter.class)));
        assertTrue(behind.getMessage().contains("apiMinor"), behind.getMessage());

        write(SERVICE_FILE, NewerMinorAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", MANIFEST.replace("\"apiMinor\": 0,",
                "\"apiMinor\": " + (AdapterApiVersion.MINOR + 1) + ","));
        TckViolation newer = assertThrows(TckViolation.class,
                () -> caseSixOf(loadFromTheUnit(NewerMinorAdapter.class)));
        assertTrue(newer.getMessage().contains("minor version " + (AdapterApiVersion.MINOR + 1)),
                newer.getMessage());
    }

    /**
     * The manifest names the image reference forms of the descriptor
     * ({@code capabilities.imageReferenceForms}, also when empty); the release of a version covers exactly
     * these forms. If they are missing or differ (kind, value, one more, one less), case 6 fails.
     */
    @Test
    public void aManifestWhoseImageReferenceFormsDifferFromTheDescriptorFails() throws Exception {
        write(SERVICE_FILE, FormsAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);
        write("META-INF/rmm-adapter.json", FORMS_MANIFEST);
        assertDoesNotThrow(() -> caseSixOf(loadFromTheUnit(FormsAdapter.class)));

        for (String differing : List.of(
                MINOR_ONE_MANIFEST.replace(", \"imageReferenceForms\": []", ""),
                MINOR_ONE_MANIFEST,
                FORMS_MANIFEST.replace("\"attachment\"", "\"image\""),
                FORMS_MANIFEST.replace("\"attachment\"", "\"Attachment\""),
                FORMS_MANIFEST.replace("\"SCHEME\"", "\"PATH_TEMPLATE\""),
                FORMS_MANIFEST.replace("\"value\": \"attachment\" }", "\"value\": \"attachment\" },"
                        + " { \"kind\": \"SCHEME\", \"value\": \"image\" }"),
                FORMS_MANIFEST.replace("\"imageReferenceForms\": [", "\"imageReferenceForms\": \"\", \"x\": ["))) {
            write("META-INF/rmm-adapter.json", differing);
            TckViolation violation = assertThrows(TckViolation.class,
                    () -> caseSixOf(loadFromTheUnit(FormsAdapter.class)), differing);
            assertTrue(violation.getMessage().contains("imageReferenceForms"), violation.getMessage());
        }

        write(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", FORMS_MANIFEST.replace("\"apiMinor\": 1,", "\"apiMinor\": 0,"));
        TckViolation more = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(more.getMessage().contains("imageReferenceForms"), more.getMessage());
    }

    /**
     * The scheme of an image reference form follows the rule of the host (lowercase letters, digits and
     * {@code -}, a letter first, 2 to 32 characters), not just the wider grammar of the contract. With
     * {@code a.b}, {@code a+b} or a single letter case 6 fails even if manifest and descriptor agree: the host
     * would refuse the unit on upload.
     */
    @Test
    public void aSchemeOutsideTheRuleOfTheHostFails() throws Exception {
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);
        for (java.util.Map.Entry<Class<? extends ToolAdapter>, String> refused : List.of(
                java.util.Map.<Class<? extends ToolAdapter>, String>entry(DottedSchemeAdapter.class, "tool.att"),
                java.util.Map.<Class<? extends ToolAdapter>, String>entry(PlusSchemeAdapter.class, "tool+att"),
                java.util.Map.<Class<? extends ToolAdapter>, String>entry(OneLetterSchemeAdapter.class, "x"))) {
            write(SERVICE_FILE, refused.getKey().getName() + "\n");
            write("META-INF/rmm-adapter.json",
                    FORMS_MANIFEST.replace("\"attachment\"", "\"" + refused.getValue() + "\""));
            TckViolation violation = assertThrows(TckViolation.class,
                    () -> caseSixOf(loadFromTheUnit(refused.getKey())), refused.getValue());
            assertTrue(violation.getMessage().contains("<" + refused.getValue() + ">")
                    && violation.getMessage().contains("never honours"), violation.getMessage());
        }

        write(SERVICE_FILE, HostSchemeAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", FORMS_MANIFEST.replace("\"attachment\"", "\"" + HOST_SCHEME_OF_32 + "\""));
        assertDoesNotThrow(() -> caseSixOf(loadFromTheUnit(HostSchemeAdapter.class)));
    }

    /**
     * If the descriptor declares something that arrived with minor version 1 (image reference forms, a key of
     * type {@code CLIENT_IDENTITY}, the key {@code timeout.connect}), case 6 fails with {@code apiMinor} 0,
     * even if the manifest names the same 0: a host of minor version 0 does not know it.
     */
    @Test
    public void aDescriptorWithAnAdditionOfMinorOneNeedsAtLeastApiMinorOne() throws Exception {
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);
        write(SERVICE_FILE, FormsAtMinorZeroAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", FORMS_MANIFEST.replace("\"apiMinor\": 1,", "\"apiMinor\": 0,"));
        TckViolation forms = assertThrows(TckViolation.class,
                () -> caseSixOf(loadFromTheUnit(FormsAtMinorZeroAdapter.class)));
        assertTrue(forms.getMessage().contains("image reference forms") && forms.getMessage().contains("apiMinor is 0"),
                forms.getMessage());

        write(SERVICE_FILE, ClientIdentityAtMinorZeroAdapter.class.getName() + "\n");
        write("META-INF/rmm-adapter.json", MANIFEST);
        TckViolation identity = assertThrows(TckViolation.class,
                () -> caseSixOf(loadFromTheUnit(ClientIdentityAtMinorZeroAdapter.class)));
        assertTrue(identity.getMessage().contains("<tls.client> of type CLIENT_IDENTITY"), identity.getMessage());

        write(SERVICE_FILE, ConnectTimeoutAtMinorZeroAdapter.class.getName() + "\n");
        TckViolation timeout = assertThrows(TckViolation.class,
                () -> caseSixOf(loadFromTheUnit(ConnectTimeoutAtMinorZeroAdapter.class)));
        assertTrue(timeout.getMessage().contains("<timeout.connect>"), timeout.getMessage());
        assertTrue(timeout.getMessage().contains("minor version 1"), timeout.getMessage());
    }

    /** Without a {@code ServiceLoader} entry the unit is no plugin: the case is skipped, not passed. */
    @Test
    public void aUnitThatNamesNoServiceIsNoPluginAndTheCaseIsSkipped() throws Exception {
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);

        assertThrows(TestAbortedException.class, () -> caseSixOf(loadFromTheUnit()));
    }

    /**
     * A manifest without a {@code ServiceLoader} entry for the adapter is no reason to skip: if a change to
     * the module drops the entry, the host does not load the jar, and the test of the module would pass
     * silently. The case fails and names the file and the class, also when the entry names another adapter.
     */
    @Test
    public void aUnitWithAManifestButWithoutTheServiceEntryFails() throws Exception {
        write("META-INF/rmm-adapter.json", MANIFEST);
        write("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);

        TckViolation missing = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(missing.getMessage().contains(SERVICE_FILE)
                && missing.getMessage().contains(PluginAdapter.class.getName()), missing.getMessage());

        write(SERVICE_FILE, "com.example.AnotherAdapter\n");
        TckViolation another = assertThrows(TckViolation.class, () -> caseSixOf(loadFromTheUnit()));
        assertTrue(another.getMessage().contains(SERVICE_FILE), another.getMessage());
    }

    /**
     * The case reads a packed jar like the class directory: it passes with all entries and fails without the
     * {@code ServiceLoader} entry.
     */
    @Test
    public void aPackedJarIsCheckedLikeTheDirectory() throws Exception {
        java.util.Map<String, String> complete = new java.util.LinkedHashMap<>();
        complete.put(SERVICE_FILE, PluginAdapter.class.getName() + "\n");
        complete.put("META-INF/rmm-adapter.json", MANIFEST);
        complete.put("META-INF/rmm-adapter/mapping-template.json", TEMPLATE);
        assertDoesNotThrow(() -> caseSixOf(loadFromAJar("complete.jar", complete)));

        java.util.Map<String, String> withoutService = new java.util.LinkedHashMap<>(complete);
        withoutService.remove(SERVICE_FILE);
        TckViolation violation = assertThrows(TckViolation.class,
                () -> caseSixOf(loadFromAJar("without-service.jar", withoutService)));
        assertTrue(violation.getMessage().contains("without-service.jar")
                && violation.getMessage().contains(SERVICE_FILE), violation.getMessage());
    }

    /** {@link PluginAdapter} from a jar with these entries, with the jar as its code source. */
    private ToolAdapter loadFromAJar(String name, java.util.Map<String, String> entries) throws Exception {
        String resource = PluginAdapter.class.getName().replace('.', '/') + ".class";
        Path jar = unit.resolve(name);
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar));
             InputStream bytes = PluginAdapter.class.getClassLoader().getResourceAsStream(resource)) {
            out.putNextEntry(new java.util.jar.JarEntry(resource));
            out.write(bytes.readAllBytes());
            out.closeEntry();
            for (java.util.Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new java.util.jar.JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        UnitLoader loader = new UnitLoader(jar.toUri().toURL(), PluginAdapter.class.getClassLoader());
        return (ToolAdapter) loader.loadClass(PluginAdapter.class.getName()).getDeclaredConstructor().newInstance();
    }

    private static void caseSixOf(ToolAdapter adapter) throws Exception {
        new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapter;
            }
        }.aPluginUnitCarriesAManifestMatchingItsDescriptorAndItsMappingTemplate();
    }

    private void write(String entry, String content) throws IOException {
        Path file = unit.resolve(entry);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** {@link PluginAdapter} from the directory of the unit, with the unit as its code source. */
    private ToolAdapter loadFromTheUnit() throws Exception {
        return loadFromTheUnit(PluginAdapter.class);
    }

    /** This adapter from the directory of the unit, with the unit as its code source. */
    private ToolAdapter loadFromTheUnit(Class<? extends ToolAdapter> adapter) throws Exception {
        String resource = adapter.getName().replace('.', '/') + ".class";
        Path classFile = unit.resolve(resource);
        Files.createDirectories(classFile.getParent());
        try (InputStream bytes = adapter.getClassLoader().getResourceAsStream(resource)) {
            Files.write(classFile, bytes.readAllBytes());
        }
        UnitLoader loader = new UnitLoader(unit.toUri().toURL(), adapter.getClassLoader(), adapter.getName());
        return (ToolAdapter) loader.loadClass(adapter.getName()).getDeclaredConstructor().newInstance();
    }

    /** Loads only the adapter itself from the unit, everything else (contract, JDK) from the parent. */
    private static final class UnitLoader extends URLClassLoader {

        private final String adapter;

        UnitLoader(URL unit, ClassLoader parent) {
            this(unit, parent, PluginAdapter.class.getName());
        }

        UnitLoader(URL unit, ClassLoader parent, String adapter) {
            super(new URL[]{unit}, parent);
            this.adapter = adapter;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(adapter)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : findClass(name);
            }
        }
    }
}
