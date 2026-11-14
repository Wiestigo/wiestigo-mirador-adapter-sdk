package com.example.adapter.csvtool;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.ElementFailure;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Example adapter: reads requirements from a CSV file
 * ({@code id;type;name;description}, first line is the header) and delivers
 * them to the host as {@link ElementArtifact}s. It is the smallest complete
 * read adapter the contract allows.
 *
 * <p>Two sources: a file of the operator ({@code source.file}, scope
 * {@code PLATFORM}) or, as with a real tool, an address of the connection
 * ({@code csv.url}, scope {@code BINDING}) read through
 * {@code context.http()}. The host's HTTP path handles address checks, TLS,
 * rate limiting, retries and deadlines; an adapter never builds an HTTP
 * client of its own (TCK case 2).
 *
 * <p>To use it as a template, replace the CSV reading with the API of your
 * tool. Descriptor, configuration keys, cursor scheme and the
 * {@code ServiceLoader} registration ({@code META-INF/services}) keep their
 * shape. Mirador does the rest: mapping to its own types and attributes
 * (from the mapping template in the jar,
 * {@code META-INF/rmm-adapter/mapping-template.json}, copied for each
 * connection), idempotency, batching, hierarchy, progress and
 * administration.
 */
public final class CsvToolAdapter implements ToolAdapter {

    /** Columns of the example CSV; a real tool has its field API instead. */
    private static final int COLUMN_COUNT = 4;
    private static final String SEPARATOR = ";";

    @Override
    public AdapterDescriptor descriptor() {
        return new AdapterDescriptor(
                "CSVTOOL",
                "CSV Example Tool",
                "1.0.0",
                AdapterApiVersion.MAJOR,
                new AdapterCapabilities(
                        //Read only: a CSV file is not a target system
                        Set.of(SyncDirection.TO_RMM),
                        //The file has no hierarchy: everything goes below the sync point
                        HierarchyModel.FLAT,
                        false, false, false,
                        //No "changed since": every row counts in every run
                        false,
                        SyncPhase.artifactsOnly(),
                        java.util.Set.of(),
                        Set.of(), null),
                List.of(
                        //A path in the host's file system belongs to the operator:
                        //PATH only in scope PLATFORM
                        ConfigKey.platform("source.file", ConfigType.PATH, "",
                                "CSV file with the requirements (id;type;name;description, header line)."
                                        + " Relative to the configuration directory. Empty when csv.url is set."),
                        //A real tool declares its address as a URL in scope BINDING:
                        //the type tells the host that the value is an address (address
                        //check, fresh second factor when it is saved)
                        ConfigKey.optional("csv.url", ConfigType.URL, "",
                                "Address of the CSV file at the tool, read through the host's HTTP path."
                                        + " When set, it takes precedence over source.file."),
                        ConfigKey.optional("page.size", ConfigType.INTEGER, "50",
                                "Rows per delivered page.")), List.of(),
                //Minor version of the contract the adapter is built against; the manifest names the same number
                //(apiMinor). A literal, never the MINOR constant of the contract: that constant grows with every
                //contract version you compile against, although the adapter uses none of the additions, and
                //descriptor and manifest would drift apart. Raise it only when the adapter uses something new.
                1);
    }

    @Override
    public AdapterInstance bind(AdapterContext context) {
        int pageSize = Math.max(1, context.config().integer("page.size"));
        CsvSource source;
        if (!context.config().string("csv.url").isBlank()) {
            //The HTTP path of the binding: the same context always returns the same object
            URI address = context.config().url("csv.url");
            context.logger().info("CSV source: " + AdapterRequest.forLog(address));
            source = new HttpSource(context.http(), address);
        } else if (!context.config().string("source.file").isBlank()) {
            Path sourceFile = context.config().path("source.file");
            context.logger().info("CSV source: " + sourceFile);
            source = new FileSource(sourceFile);
        } else {
            throw new IllegalArgumentException("Configure csv.url (the CSV file of the tool) or source.file"
                    + " (a CSV file of the operator)");
        }
        CsvReader reader = new CsvReader(source, pageSize);
        return new AdapterInstance() {
            @Override
            public Optional<SourceReader> reader() {
                return Optional.of(reader);
            }

            @Override
            public Optional<TargetWriter> writer() {
                return Optional.empty();
            }

            @Override
            public void checkHealth() throws AdapterException {
                source.lines();
            }
        };
    }

    /** Where the rows come from: a file of the operator or an address at the tool. */
    private interface CsvSource {
        /** All lines of the file, including the header line. */
        List<String> lines() throws AdapterException;
    }

    /** The file of the operator. */
    private record FileSource(Path sourceFile) implements CsvSource {

        @Override
        public List<String> lines() throws AdapterException {
            if (!Files.isReadable(sourceFile)) {
                throw new AdapterException("CSV file <" + sourceFile + "> is not readable");
            }
            try {
                return Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AdapterException("Reading the CSV file <" + sourceFile + "> failed: "
                        + e.getMessage(), e);
            }
        }
    }

    /**
     * The file at the tool, fetched again for every page through
     * {@code context.http()}: the cursor stays an offset and so survives any
     * pause of the run. The host enforces deadlines, stop and address checks;
     * a transport failure already arrives as an {@link AdapterException}.
     */
    private record HttpSource(AdapterHttp http, URI address) implements CsvSource {

        @Override
        public List<String> lines() throws AdapterException {
            AdapterResponse response = http.send(AdapterRequest.get(address));
            if (!response.successful()) {
                //Overloaded or failing: transient; anything else (404, 401) is final
                boolean transientFailure = response.status() == 429 || response.status() >= 500;
                throw new AdapterException("Reading the CSV file <" + AdapterRequest.forLog(address)
                        + "> failed with status " + response.status(), null, transientFailure,
                        response.status());
            }
            return response.bodyAsString().lines().toList();
        }
    }

    /** Offset cursor over the data rows; broken rows become {@link ElementFailure}s. */
    private static final class CsvReader implements SourceReader {

        private final CsvSource source;
        private final int pageSize;

        private CsvReader(CsvSource source, int pageSize) {
            this.source = source;
            this.pageSize = pageSize;
        }

        @Override
        public SourcePage<ElementArtifact> readArtifacts(SyncRunContext context, ReadScope scope,
                                                         PageCursor cursor) throws AdapterException {
            List<String> dataRows = dataRows();
            if (scope.isExplicit()) {
                //Explicit scope ("these identifiers"): only the rows whose id is named;
                //a named id without a row is not delivered (the tool no longer has it)
                dataRows = dataRows.stream()
                        .filter(row -> scope.elementIds().contains(row.split(SEPARATOR, 2)[0].trim()))
                        .toList();
            }
            //A file that shrank between two pages leaves the cursor behind its end: an empty last page
            int startIndex = Math.min(cursor == null ? 0 : rowOffsetOf(cursor), dataRows.size());
            int endIndex = Math.min(startIndex + pageSize, dataRows.size());

            List<ElementArtifact> artifacts = new ArrayList<>();
            List<ElementFailure> failures = new ArrayList<>();
            for (int rowIndex = startIndex; rowIndex < endIndex; rowIndex++) {
                //Failure isolation per row: a broken row does not take the rest of
                //the file with it; the run reports it as a single failure
                try {
                    artifacts.add(toArtifact(dataRows.get(rowIndex)));
                } catch (Exception e) {
                    failures.add(new ElementFailure("row " + (rowIndex + 2),
                            "Unreadable CSV row: " + e.getMessage(), e));
                }
            }
            boolean lastPage = endIndex >= dataRows.size();
            return new SourcePage<>(artifacts, failures, endIndex - startIndex,
                    lastPage ? null : new PageCursor(String.valueOf(endIndex)), lastPage);
        }

        /**
         * The row offset a cursor of this reader carries. The host stores the
         * cursor between two pages and hands it back unread; one that is
         * damaged or follows another scheme (an older adapter version) is a
         * failure of the read like any other, never an unchecked exception.
         */
        private static int rowOffsetOf(PageCursor cursor) throws AdapterException {
            try {
                int offset = Integer.parseInt(cursor.value());
                if (offset >= 0) {
                    return offset;
                }
            } catch (NumberFormatException notANumber) {
                //Reported below, like a negative offset
            }
            throw new AdapterException("The cursor <" + cursor.value() + "> is not a row offset of the CSV file");
        }

        //readRelationships/readComments are not implemented: the declaration
        //(reads = ARTIFACTS) tells the host that a CSV file has neither links
        //nor comments, so it never calls those phases

        private List<String> dataRows() throws AdapterException {
            return source.lines().stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .toList();
        }

        /**
         * Converts a row into an {@link ElementArtifact} in the terms of the
         * tool: the type goes into the field "Type" (the mapping names it as
         * the type carrier with {@code artifactTypeField}), name and
         * description under their tool names. Translating them into Mirador
         * types and attributes is the job of the mapping, not of the adapter.
         */
        private ElementArtifact toArtifact(String row) {
            String[] columns = row.split(SEPARATOR, -1);
            if (columns.length < COLUMN_COUNT) {
                throw new IllegalArgumentException("expected " + COLUMN_COUNT + " columns, found "
                        + columns.length);
            }
            ElementArtifact artifact = new ElementArtifact();
            artifact.setId(new ElementId(columns[0].trim()));
            artifact.addField(field("Type", columns[1].trim()));
            artifact.addField(field("Name", columns[2].trim()));
            artifact.addField(field("Description", columns[3].trim()));
            return artifact;
        }

        private ElementFieldString field(String label, String value) {
            return new ElementFieldString(new ElementName(label), new ElementLabel(label), value);
        }
    }
}
