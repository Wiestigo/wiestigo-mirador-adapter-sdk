package com.example.adapter.csvtool;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.FakeHttpTool;
import com.rmm.adapter.tck.ReadContract;
import com.rmm.adapter.tck.TckAdapterContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the TCK against the example adapter. It is the pattern for the test
 * class of your own adapter: extend {@link AdapterContractTest} and supply
 * the adapter and its test configuration.
 *
 * <p>The "tool" is a {@link FakeHttpTool} that serves the CSV files from the
 * test resources and answers only requests that come through
 * {@code context.http()} (TCK case 2). That is why the live checks of the
 * TCK run here as well (cursor semantics, deadline, pause between two pages,
 * {@code purge()}); for a real tool, {@code liveRunContext()} points to a
 * test project or stays empty.
 */
public class TestCsvToolAdapterContract extends AdapterContractTest {

    private FakeHttpTool tool;

    @BeforeEach
    public void startTheTool() throws IOException {
        tool = FakeHttpTool.start()
                .text("/example.csv", "text/csv; charset=utf-8", resource("example.csv"))
                .text("/second_tool.csv", "text/csv; charset=utf-8", resource("second_tool.csv"));
    }

    @AfterEach
    public void stopTheTool() {
        tool.close();
    }

    @Override
    protected ToolAdapter adapter() {
        return new CsvToolAdapter();
    }

    @Override
    protected Map<String, String> configuration() {
        return Map.of(
                "csv.url", tool.uri("/example.csv").toString(),
                //Page size 2 with 5 rows: the read contract sees several pages
                "page.size", "2");
    }

    /**
     * TCK case 1: a second binding of the same adapter class to another file
     * with a different data set at the same tool, otherwise identical (a
     * different page size alone would prove nothing). Both bindings read in
     * parallel and nothing may cross over. Case 2 also checks that each
     * binding sends through its own HTTP path.
     */
    @Override
    protected Optional<Map<String, String>> secondBindingConfiguration() {
        return Optional.of(Map.of(
                "csv.url", tool.uri("/second_tool.csv").toString(),
                "page.size", "2"));
    }

    /** TCK case 2: the tool that {@link #configuration()} reaches. */
    @Override
    protected Optional<FakeHttpTool> networkTool() {
        return Optional.of(tool);
    }

    @Override
    protected Optional<SyncRunContext> liveRunContext() {
        return Optional.of(new SyncRunContext(UUID.randomUUID(), "CSV-PRJ"));
    }

    /**
     * The pause between two pages: the CSV tool delivers the whole file with
     * every request and keeps no state between two requests that could
     * expire (no search session, no result set); the cursor is an offset. A
     * tool with such state registers it with {@link FakeHttpTool#onExpiry}
     * and does not override this method. With neither, the case fails
     * instead of passing silently.
     */
    @Override
    protected void expireToolState() {
        //Nothing expires: the tool keeps no state between two requests
    }

    private static Path testCsv(String name) {
        URL resource = TestCsvToolAdapterContract.class.getResource("/" + name);
        if (resource == null) {
            throw new IllegalStateException("Test resource " + name + " is missing");
        }
        try {
            return Paths.get(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Test resource " + name + " is not a path", e);
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = TestCsvToolAdapterContract.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Test resource " + name + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Tests of the adapter itself, in addition to the contract
    // ------------------------------------------------------------------

    @Test
    public void deliversEveryRowAsAnArtifactInToolTerms() throws Exception {
        TckAdapterContext context = TckAdapterContext.of(adapter().descriptor(), configuration());
        var instance = adapter().bind(context);
        try {
            var result = ReadContract.readAll("readArtifacts",
                    instance.reader().orElseThrow()::readArtifacts,
                    liveRunContext().orElseThrow(), ReadScope.full(), 100);

            assertEquals(5, result.elements().size(), "every data row becomes an artifact");
            assertEquals(3, result.pages(), "5 rows with page size 2 are 3 pages");
            var first = result.elements().get(0);
            assertEquals("REQ-1", first.getIdValue());
            assertEquals("Requirement", first.getFieldByName("Type").getValue(),
                    "the type of the tool is in the artifactTypeField field");
            assertEquals("Brake", first.getFieldByName("Name").getValue());
            assertEquals(3, context.httpRequests(), "one request per page, all through context.http()");
        } finally {
            instance.close();
            context.release();
        }
    }

    /**
     * The explicit scope ("these identifiers") delivers exactly the named rows;
     * a named id without a row is left out.
     */
    @Test
    public void namedIdsDeliverExactlyTheNamedRows() throws Exception {
        var instance = adapter().bind(TckAdapterContext.of(adapter().descriptor(), configuration()));
        try {
            var result = ReadContract.readAll("readArtifacts(ids)",
                    instance.reader().orElseThrow()::readArtifacts,
                    liveRunContext().orElseThrow(),
                    ReadScope.ofIds(List.of("REQ-2", "REQ-4", "REQ-99")), 100);

            assertEquals(List.of("REQ-2", "REQ-4"),
                    result.elements().stream().map(element -> element.getIdValue()).toList(),
                    "exactly the named rows, in file order; REQ-99 does not exist");
            assertTrue(result.failures().isEmpty(), "a missing identifier is not a failure");
        } finally {
            instance.close();
        }
    }

    /**
     * Without {@code csv.url} the adapter reads the file of the operator: a
     * value of the deployment, never of the connection.
     */
    @Test
    public void withoutAnAddressTheFileOfTheOperatorIsRead() throws Exception {
        TckAdapterContext context = TckAdapterContext.builder(adapter().descriptor())
                .configuration(Map.of("page.size", "50"))
                .platform(Map.of("source.file", testCsv("second_tool.csv").toString()))
                .build();
        var instance = adapter().bind(context);
        try {
            var result = ReadContract.readAll("readArtifacts",
                    instance.reader().orElseThrow()::readArtifacts,
                    liveRunContext().orElseThrow(), ReadScope.full(), 100);

            assertEquals(3, result.elements().size());
            assertEquals(0, context.httpRequests(), "a file needs no HTTP path");
        } finally {
            instance.close();
            context.release();
        }
    }

    @Test
    public void aFailingToolIsATransientFailureOnlyWhenItIsOverloaded() throws Exception {
        tool.route("/broken.csv", exchange -> FakeHttpTool.respond(exchange, 503, "text/plain", new byte[0]))
                .route("/missing.csv", exchange -> FakeHttpTool.respond(exchange, 404, "text/plain", new byte[0]));
        for (String file : List.of("broken.csv", "missing.csv")) {
            var instance = adapter().bind(TckAdapterContext.of(adapter().descriptor(),
                    Map.of("csv.url", tool.uri("/" + file).toString())));
            try {
                var failed = assertThrows(AdapterException.class,
                        () -> instance.reader().orElseThrow().readArtifacts(liveRunContext().orElseThrow(),
                                ReadScope.full(), null));

                assertEquals(file.equals("broken.csv"), failed.isRetryable(), file + ": " + failed.getMessage());
            } finally {
                instance.close();
            }
        }
    }

    @Test
    public void aBrokenRowIsReportedAsFailureAndDoesNotStopTheRun() throws Exception {
        Path csv = Files.createTempFile("csvtool", ".csv");
        Files.writeString(csv, "id;type;name;description\n"
                + "REQ-1;Requirement;Brake;brakes\n"
                + "BROKEN-without-columns\n"
                + "REQ-2;Requirement;Steering;steers\n");
        var instance = adapter().bind(TckAdapterContext.builder(adapter().descriptor())
                .configuration(Map.of("page.size", "50"))
                .platform(Map.of("source.file", csv.toString()))
                .build());
        try {
            var result = ReadContract.readAll("readArtifacts",
                    instance.reader().orElseThrow()::readArtifacts,
                    liveRunContext().orElseThrow(), ReadScope.full(), 100);

            assertEquals(2, result.elements().size(), "the intact rows arrive");
            assertEquals(1, result.failures().size(), "the broken row is reported as a single failure");
            assertTrue(result.failures().get(0).message().contains("CSV row"));
        } finally {
            instance.close();
            Files.deleteIfExists(csv);
        }
    }

    /**
     * A cursor is stored between two pages. One that is not a row offset
     * (damaged, or written by an adapter with another cursor scheme) fails
     * the read with an {@link AdapterException}, which the host reports on
     * the run; an unchecked exception would count as a defect of the adapter.
     */
    @Test
    public void aCursorThatIsNoRowOffsetFailsTheReadWithAnAdapterException() throws Exception {
        var instance = adapter().bind(TckAdapterContext.of(adapter().descriptor(), configuration()));
        try {
            for (String foreign : List.of("page-2", "2 ", "1.5", "-2", "99999999999")) {
                AdapterException failed = assertThrows(AdapterException.class,
                        () -> instance.reader().orElseThrow().readArtifacts(liveRunContext().orElseThrow(),
                                ReadScope.full(), new PageCursor(foreign)), foreign);

                assertTrue(failed.getMessage().contains("<" + foreign + ">"), failed.getMessage());
                assertFalse(failed.isRetryable(), "the same cursor fails again: " + foreign);
            }
        } finally {
            instance.close();
        }
    }

    /** The cursor of a page is the offset of the next row: reading from it continues exactly there. */
    @Test
    public void theCursorOfAPageContinuesWithTheNextRow() throws Exception {
        var instance = adapter().bind(TckAdapterContext.of(adapter().descriptor(), configuration()));
        try {
            var first = instance.reader().orElseThrow().readArtifacts(liveRunContext().orElseThrow(),
                    ReadScope.full(), null);
            var second = instance.reader().orElseThrow().readArtifacts(liveRunContext().orElseThrow(),
                    ReadScope.full(), first.nextCursor());

            assertEquals(new PageCursor("2"), first.nextCursor());
            assertEquals(List.of("REQ-3", "REQ-4"),
                    second.elements().stream().map(element -> element.getIdValue()).toList());
        } finally {
            instance.close();
        }
    }

    /** A file that shrank between two pages leaves the cursor behind its end: the read ends with an empty page. */
    @Test
    public void aCursorBehindTheEndOfTheFileEndsTheReadWithAnEmptyPage() throws Exception {
        var instance = adapter().bind(TckAdapterContext.of(adapter().descriptor(), configuration()));
        try {
            var page = instance.reader().orElseThrow().readArtifacts(liveRunContext().orElseThrow(),
                    ReadScope.full(), new PageCursor("1000"));

            assertTrue(page.lastPage());
            assertEquals(List.of(), page.elements());
            assertEquals(0, page.readCount());
        } finally {
            instance.close();
        }
    }

    /**
     * The minor version of the descriptor is a literal in the code, equal to
     * {@code apiMinor} in the manifest, and never
     * {@code AdapterApiVersion.MINOR}: compiled against a newer contract
     * version, the descriptor would otherwise name the minor version of that
     * contract while the manifest still names the old one. The check reads
     * the source because bytecode holds the value of a constant, not its
     * name; it finds the source from the compiled class
     * ({@link #sourceOf}), whatever the working directory of the test run.
     */
    @Test
    public void theMinorVersionIsANumberInTheCodeNotTheConstantOfTheContract() throws IOException {
        String source = Files.readString(sourceOf(CsvToolAdapter.class), StandardCharsets.UTF_8);
        assertTrue(!source.contains("AdapterApiVersion.MINOR"), "CsvToolAdapter names AdapterApiVersion.MINOR");
        assertTrue(resource("META-INF/rmm-adapter.json").contains("\"apiMinor\": "
                + new CsvToolAdapter().descriptor().apiMinorVersion() + ","), "apiMinor as in the descriptor");
    }

    /**
     * The source file of a class of this project: {@code src/main/java/…}
     * below the first directory above the compiled class that has it (the
     * project directory, two levels above {@code target/classes}).
     */
    private static Path sourceOf(Class<?> type) {
        String file = "src/main/java/" + type.getName().replace('.', '/') + ".java";
        Path compiled;
        try {
            compiled = Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The classes of " + type.getName() + " are not in a directory", e);
        }
        for (Path directory = compiled.getParent(); directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve(file))) {
                return directory.resolve(file);
            }
        }
        throw new IllegalStateException("No " + file + " above " + compiled);
    }
}
