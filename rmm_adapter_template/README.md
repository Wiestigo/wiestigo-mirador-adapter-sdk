# Adapter template

This project is the starting point for your own Wiestigo Mirador adapter: copy
it, replace the example and build a jar. It contains

- `CsvToolAdapter`, a complete read adapter that delivers the rows of a CSV
  file as artifacts. It reads the file from the tool through `context.http()`
  (`csv.url`) or from a file of the operator (`source.file`);
- `TestCsvToolAdapterContract`, which runs the contract tests of the TCK
  against the adapter, plus a few tests of the adapter itself;
- `TestCsvToolNetworkPathRule`, an ArchUnit rule that keeps HTTP clients out
  of the adapter;
- the manifest, the mapping template and the `ServiceLoader` file under
  `src/main/resources/META-INF`;
- a `pom.xml` without a parent that builds the deliverable jar.

The full guide is `docs/DEVELOPER_GUIDE.md` in the SDK repository.

## Build and test

The template needs the SDK artifacts in a Maven repository. Either build the
SDK from source once, in a checkout of the SDK with any JDK from 17:

```bash
mvn install
```

or take the released packages: add the package repository of the SDK to the
`pom.xml` of your copy and a token to your `settings.xml`, as the README of
the SDK repository describes under "Getting the SDK". The property
`rmm.adapter.api.version` in the `pom.xml` names the SDK version; the copy in
the zip of a release already carries the version of that release.

After that the template, and every copy of it, builds on its own:

```bash
mvn test       # contract tests (TCK) and the tests of the adapter
mvn package    # the same, then target/csvtool-adapter-1.0.0.jar
```

The tests need no running Mirador and no network: the "tool" is a
`FakeHttpTool` on localhost that serves the CSV files from
`src/test/resources`.

The template depends on two SDK artifacts:

| Artifact | Scope | Why |
| --- | --- | --- |
| `com.wiestigo.mirador:rmm_adapter_api:1.1.0` | `provided` | The contract. The host supplies it at run time. |
| `com.wiestigo.mirador:rmm_adapter_tck:1.1.0` | `test` | The contract tests. |

## Make it your own adapter

1. Copy the project and change `groupId`, `artifactId` and `version` in
   `pom.xml`.
2. Replace `CsvToolAdapter` with a class for your tool:
   - `descriptor()` declares the type key (`systemKey`), the display name, the
     capabilities and the configuration keys. A file path is always a
     `ConfigKey.platform(...)` key; the address of the tool is a key of type
     `URL`.
   - The last constructor argument is the minor version of the contract the
     adapter needs. Write it as a number (the template uses `1`), never as
     `AdapterApiVersion.MINOR`, and raise it only when the adapter uses
     something from a newer minor version.
   - `bind(context)` builds what one binding needs and returns a
     `SourceReader` and, for an adapter that also writes to the tool, a
     `TargetWriter`.
3. Enter the fully qualified name of your class in
   `src/main/resources/META-INF/services/com.rmm.adapter.api.ToolAdapter`.
4. Update `src/main/resources/META-INF/rmm-adapter.json`. The manifest must
   match the descriptor: `typeKey` (the `systemKey`), `name`, `version`,
   `apiMajor`, `apiMinor`, the configuration keys in the order of the
   descriptor (with type, scope and `required`), and under `capabilities` the
   directions, the hierarchy model and `imageReferenceForms` (`[]` if the
   adapter declares none). `vendor` must not be empty. The host rejects
   unknown fields, except `_comment`.
5. Update `src/main/resources/META-INF/rmm-adapter/mapping-template.json`. It
   translates the types and field names of your tool into those of Mirador.
   Every new connection starts with a copy of it.
6. Adapt the tests:
   - In the contract test, return your adapter from `adapter()` and its test
     configuration from `configuration()`. Values of the operator (keys in
     scope `PLATFORM`) go into `platformConfiguration()`.
   - Let `secondBindingConfiguration()` point to a second data set of the same
     tool, with a different content. The TCK binds both in parallel and
     checks that nothing crosses over.
   - Return the fake tool from `networkTool()`. It answers only requests that
     come through `context.http()`.
   - `liveRunContext()` names a test project of the tool and switches on the
     tests that read and write. While it is empty, the TCK skips them.
   - In `pom.xml`, rename the variables under `environmentVariables` after
     your configuration keys. They are traps: the TCK fails when one of their
     values shows up in the adapter's output.
   - Change `ADAPTER_PACKAGE` in the network rule test, and the source path in
     `theMinorVersionIsANumberInTheCodeNotTheConstantOfTheContract`.
7. Run `mvn package` and hand the jar from `target/` to the operator of the
   Mirador installation, who uploads and releases it. Every new build needs a
   new release.

## Rules the tests enforce

The host loads an adapter once per process and binds it once per connection,
for many tenants at the same time. Therefore:

- State lives in the `AdapterInstance` or in `context.store()`. No static or
  process-wide clients, caches or flags, and none in fields of the
  `ToolAdapter` class.
- Configuration comes only from `context.config()`: no system properties, no
  environment variables, no files of your own.
- Network traffic goes only through `context.http()`. The host handles
  address checks, TLS, rate limits, retries and deadlines, so the adapter
  needs no HTTP client and no retry loop.
- Threads come only from `context.executor()`; intermediate state of a run
  belongs in `context.runStore()`, reloadable data in `context.caches()`.
- `close()` releases everything the binding built. The host may bind the
  same connection again afterwards.

A passing TCK is your own statement about your adapter. It is not a
certification by Wiestigo.

## What goes into the jar

`mvn package` builds one self-contained jar with the Maven Shade plugin:

- the classes of your adapter;
- every library you add as an ordinary `compile` dependency. The host loads
  each plugin with its own class loader, so these cannot clash with the
  libraries of the host;
- `META-INF/services/com.rmm.adapter.api.ToolAdapter`;
- `META-INF/rmm-adapter.json`;
- `META-INF/rmm-adapter/mapping-template.json`.

The contract (`rmm_adapter_api`, with the transfer model `com.io_elements`
and slf4j) stays out: it has scope `provided`, and the host supplies it. A
second copy inside the jar would break type assignments between adapter and
host.

## What Mirador does for you

The adapter reads and writes in the terms of the tool: its identifiers, its
type names, its field names. Mirador does the rest: mapping to its own types
and attributes, idempotency, batching, building the hierarchy, change
detection, parallel execution, progress and administration.

## License

This template is licensed under MIT No Attribution (MIT-0), see `LICENSE`:
use it as the starting point of your own project without keeping a copyright
notice, and license your project as you like. The SDK it builds on
(`rmm_adapter_api`, the TCKs) is licensed under the Apache License 2.0.
