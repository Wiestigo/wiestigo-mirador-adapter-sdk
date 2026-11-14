# Developer Guide: Adapters for Wiestigo Mirador

This guide takes an adapter author from an empty project to a tested, packaged adapter for Wiestigo Mirador,
the requirements management system. Technical identifiers carry `rmm` (`com.rmm.adapter.api`,
`rmm_adapter_api`, `rmm-adapter.json`).

The guide states the rules and shows the way. The detailed reference is the Javadoc of the contract (package
`com.rmm.adapter.api`) and of the TCK (`com.rmm.adapter.tck.AdapterContractTest`). Limits that the host
enforces are given with the defaults of a host with contract 1.1; an operator can change some of them.

| What | Where |
| --- | --- |
| The contract; the transfer model and the mapping | `rmm_adapter_api`, package `com.rmm.adapter.api`; `rmm_adapter_model`, package `com.io_elements` |
| A project to copy (CSV example, shade build) | [`rmm_adapter_template/`](../rmm_adapter_template/README.md) |
| The contract tests | `rmm_adapter_tck`, `AdapterContractTest` |
| Reference adapters | `com.rmm.adapter.tck.reference.InMemoryToolAdapter` (both directions, in memory) and `NetworkToolAdapter` (reads over HTTP; uses caches, run store and executor) |
| Remote services | [REMOTE_PROTOCOL.md](REMOTE_PROTOCOL.md), `rmm_remote_tck`, [`rmm_remote_service_template/`](../rmm_remote_service_template/README.md) |

## 1. What an adapter is

An adapter connects Mirador with one external tool. The running Mirador process that loads and calls it is
called the host. The adapter talks to the tool's API and translates between that API and Mirador's
tool-neutral transfer model: `ElementArtifact`, `ElementRelationship`, `ElementComment` and
`ElementAttachment`, each with typed fields such as `ElementFieldString`.

An adapter reads and writes in the terms of its tool: the tool's identifiers, type names, field labels and
values. Everything else is done by Mirador and is not adapter code:

- mapping tool types, attributes and values to Mirador's (section 8);
- idempotence through external references, batching, parallel writes, and the order of creates;
- building the hierarchy, change detection, set reconciliation in full runs, and receipts;
- scheduling, queues, progress, run reports and administration.

## 2. Two ways to integrate

- An **in-process plugin** is one jar that the host loads. It is written in Java (17 or later) against
  `rmm_adapter_api`, tested with `rmm_adapter_tck`, and started from `rmm_adapter_template`.
- A **remote service** is an HTTPS service of your own, in any language, that Mirador addresses with the
  remote protocol. It is tested with `rmm_remote_tck` and started from `rmm_remote_service_template`.

A plugin runs with the rights of the host process; the class loader boundary (section 3) guards against
version conflicts and is not a security boundary. In multi-tenant operation, only plugins that the operator is
responsible for run in process; a customer's adapter runs there as a remote service. Choose the remote service
as well when the adapter is not written in Java or must run in your own network. Sections 3 to 12 describe the
plugin, section 13 the remote service.

## 3. Setting up a plugin project

Copy `rmm_adapter_template/` and follow its README: rename `groupId` and `artifactId`, replace
`CsvToolAdapter`, and update the service file. The template has no parent POM and builds on its own once the
SDK artifacts are in your Maven repository (`mvn install` in the SDK).

- **Dependencies:** `com.wiestigo.mirador:rmm_adapter_api:1.1.0` in scope `provided` and
  `com.wiestigo.mirador:rmm_adapter_tck:1.1.0` in scope `test`. The host supplies the contract at run time; it
  must not be in your jar, or the contract classes would exist twice. The TCK does not bring JUnit: your
  project sets the version (the template uses JUnit 6; JUnit 5 works too).
- **Java 17:** the contract is compiled for Java 17, so an adapter builds with any JDK from 17. The host runs
  on a newer Java (currently 25) and loads the adapter there.
- **Shade build:** the deliverable is one self-contained jar. Your libraries are ordinary `compile`
  dependencies; `maven-shade-plugin` packs them and merges their `META-INF/services` files
  (`ServicesResourceTransformer`).

**One jar per plugin unit.** A plugin unit is exactly one jar of at most 50 MiB. The host does not load a
directory of jars and does not follow symbolic links. `Class-Path` in `MANIFEST.MF`, `META-INF/versions/**`
(multi-release) and signature files (`*.SF`, `*.RSA`, `*.DSA`, `*.EC`) are ignored.

The jar contains:

- `META-INF/services/com.rmm.adapter.api.ToolAdapter` naming your class, which needs a no-argument
  constructor;
- the manifest `META-INF/rmm-adapter.json` and the mapping template
  `META-INF/rmm-adapter/mapping-template.json` (section 8);
- your classes and libraries, including `rmm_adapter_http_spring` and your own Spring version if you use the
  Spring facade. Code that relies on `@PostConstruct` or `@PreDestroy` needs `jakarta.annotation` in the jar;
  otherwise these methods silently do not run.

It does not contain the contract (`rmm_adapter_api`, `rmm_adapter_model`), logging bindings (the host supplies
`slf4j-api`), Spring Boot, a servlet container, Netty, a database driver, or test libraries.

**The class loader boundary.** A plugin sees only the JDK, `com.rmm.adapter.api`, `com.io_elements` and
`org.slf4j`. Spring, Jackson and everything else of the host are invisible; bundle what you need.

- The host sets the plugin's class loader as the context class loader on every call into the plugin. JVM-wide
  facilities that capture it on first use (`ServiceLoader` without an explicit loader, JAXP factories) then
  see your classes; test such places against the real jar.
- Classes and resources are served from memory, from the verified copy of the jar; resource URLs have the form
  `rmm-plugin:<sha256>!/<path>`. `getResource`, `getResourceAsStream` and `ServiceLoader` work, but there is
  no `java.io.File` over your jar. Read your own files as streams.
- The host does not unload a plugin at run time; a new jar takes effect at the next start of the host.

## 4. Descriptor and capabilities

The entry point implements `ToolAdapter`. `descriptor()` is constant: no I/O, no configuration. The host reads
it before it binds anything.

```java
public final class MyToolAdapter implements ToolAdapter {

    @Override
    public AdapterDescriptor descriptor() {
        return new AdapterDescriptor(
                "MYTOOL", "My Tool", "1.0.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(
                        Set.of(SyncDirection.TO_RMM),
                        HierarchyModel.FLAT,
                        false, false, false, false,
                        SyncPhase.artifactsOnly(), Set.of(),
                        Set.of(), new RateLimit(20, 3)),
                List.of(
                        ConfigKey.required("home.url", ConfigType.URL, "Base URL of the tool."),
                        ConfigKey.required("api.token", ConfigType.SECRET, "Token of the integration account."),
                        ConfigKey.optional("page.size", ConfigType.INTEGER, "50", "Elements per page.")),
                List.of(),
                //Minor version of the contract the adapter is built against: a literal, see section 12
                1);
    }
```

- **`systemKey`** (letters, digits, `-`, `_`) becomes the system key of the external references at Mirador's
  elements and the `typeKey` of the manifest. Never change it after the first productive run: existing
  elements would lose their link to the tool.
- **`identifierKinds`** (may be empty, never null) describe how people or the tool name an artifact in a
  reference field, for example a key such as `PROJ-12`:
  `IdentifierKind.of(name, fieldLabel, pattern, description)`, with `withSample("PROJ-12")` when the pattern
  does not read the default sample `ABC-123`. The TCK checks that the format writes what the pattern reads.

`AdapterCapabilities` states what the tool can do; the host chooses its strategy from it.

| Component | Meaning |
| --- | --- |
| `directions` | `TO_RMM` (reading) and/or `FROM_RMM` (writing back); at least one |
| `hierarchyModel` | `NATIVE_TREE`: each artifact carries an `ElementArtifactLocation`. `PATH_ATTRIBUTE`: the hierarchy is a path such as `A/B/C` in one attribute, which the bound instance names in `pathHierarchy()`. `FLAT`: everything below the sync point |
| `supportsRelationshipUpdate` | `false`: the host changes a link by creating the new one and then deleting the old one |
| `supportsArtifactTypeChange` | `true` only if the tool can change the type of an existing element; then override `convertArtifact`. With `false`, type conversions fail visibly unless the administrator allows delete and re-create for the connection |
| `supportsPartialUpdate` | `true` only if an update that carries some fields leaves the others untouched; the host then sends only changed attributes. With `false`, the safe default, it always sends the full state |
| `supportsDeltaRead` | `true` only if the tool can filter "changed since"; otherwise the host uses full runs |
| `reads`, `writes` | The phases (`SyncPhase`) served per direction. With a direction, `ARTIFACTS` is mandatory; without it, the set is empty. `SyncPhase.all()` is artifacts, relationships and comments; `ATTACHMENTS` and `VARIABILITY` are declared explicitly (`allWithAttachments()`, `allWithVariability()`) |
| `bulkOperations` | The `BulkOperation`s whose batch hooks the adapter overrides (section 9) |
| `rateLimit` | `new RateLimit(maxRequestsPerSecond, maxRetries)` or `null`; the host enforces it (section 6) |
| `imageReferenceForms` | Since contract 1.1: the forms in which the tool's rich text refers to its own images, built with `ImageReferenceForm.scheme("attachment")` or `ImageReferenceForm.pathTemplate("/{ctx}/attachment/{number}/{file}")` and added with `withImageReferenceForms(...)`. The host keeps a tool reference in one of these forms when it cannot fetch the image; any other tool reference is dropped. For a scheme the host additionally requires lower-case letters, digits and `-`, starting with a letter, 2 to 32 characters |

Rules for `reads` and `writes`:

- The host calls only declared phases. If you declare a phase, override its hooks (the TCK checks it); if you
  do not, leave the hooks alone: their defaults throw.
- In a full run, the host reconciles every declared read phase: an empty delivery means that everything is
  gone in the tool.
- Changes of an undeclared write phase are discarded in every run without transfer. If a later version
  declares the phase, they are not made up for.
- A bulk operation and `supportsRelationshipUpdate` require the matching phase in `writes`. Not declarable are
  updates of comments (comments are only created) and of attachments (replacing is delete plus create).

## 5. Declaring configuration

An adapter declares every setting as a `ConfigKey` in the descriptor, with a relative key (no `rmm.` prefix).
The tenant's administrator maintains the values per connection. In messages, name keys as declared.

| `ConfigType` | Value | Access through `context.config()` |
| --- | --- | --- |
| `STRING`, `INTEGER`, `BOOLEAN` | Plain values | `string(key)`, `integer(key)`, `bool(key)`; `limit(key)` for an `INTEGER` with a platform cap |
| `SECRET` | Token, password | `secret(key)` returns a `SecretValue`: `toString` never shows the value, `reveal()` does |
| `URL` | Address of the tool | `url(key)` returns an absolute `http` or `https` `URI` with host and without credentials |
| `CERTIFICATE` | PEM, up to 256 KiB | `certificate(key)`; see below |
| `PATH` | Path in the host's file system | `path(key)`; only in scope `PLATFORM` |
| `CLIENT_IDENTITY` | Since contract 1.1: the client identity the host creates and holds for the connection; takes no value | `string(key)` and `certificate(key)` return only the public certificate |

- **Scopes.** `ConfigScope.BINDING` (the default of `ConfigKey.required` and `ConfigKey.optional`) is a value
  of the connection. `ConfigScope.PLATFORM` (`ConfigKey.platform(...)`) is a value of the deployment, set by
  the operator and invisible to the tenant. A `PATH` key in scope `BINDING` makes the type `DEFECT`.
- **Required keys and validation.** A key without default is required; while its value is missing, the
  connection is `NOT_CONFIGURED` and the host does not bind. The administration refuses a value that the
  declared type does not accept; a connection value has at most 4000 characters. Declare addresses as `URL`
  and secrets as `SECRET`: the host recognizes them by type, checks addresses and masks secrets in the log.
- **Limits.** `limit(key)` returns the value of the connection, cut to the cap `<key>.max`, which you declare
  as a separate `INTEGER` key in scope `PLATFORM`.
- **Certificates.** Every declared `CERTIFICATE` key with a value is TLS material of the connection's HTTP
  path: without a private key a trust anchor (then only the connection's anchors are trusted), with an
  unencrypted PKCS#8 key the client certificate (at most one). Declare any other certificate as `STRING`.

**Keys the host owns:**

| Key | Rule |
| --- | --- |
| `http.rate.per.second` (`AdapterConventions.HTTP_RATE_PER_SECOND_KEY`) | Requests per second of this connection. Read only if declared, and then exactly as `INTEGER`, scope `BINDING`, default empty or a whole number from 0; optionally with the cap `http.rate.per.second.max`. Effective is the minimum of this value, the descriptor's `RateLimit` and the host's caps; 0 is the slowest rate, never "no limit" |
| `http.retries` (`AdapterConventions.HTTP_RETRIES_KEY`) | Retries per request, declared the same way. Effective is `min(value, RateLimit.maxRetries(), 3)`; 0 means no retry |
| `timeout.connect` (`AdapterConventions.CONNECT_TIMEOUT_KEY`) | Since contract 1.1: connect timeout in seconds, read if declared as `INTEGER` |

Declaring `http.rate.per.second` or `http.retries` in any other way makes the type `DEFECT` at load time.
Avoid other names starting with `http.`: the host may reserve more. The host also keeps its own keys on every
connection, among them `webhook.secret`, `project-spaces`, `conversion.delete-create`, `attachments.enabled`
and `orphaned-elements`; do not use these names. Test every declared key: a key that nobody reads accepts a
value and does nothing.

## 6. `bind` and the `AdapterContext`

`bind(context)`, the second method of `MyToolAdapter`, binds the adapter to one connection: it reads the
configuration, builds the clients and returns the `AdapterInstance`. The host loads the type once per process
and binds it once per connection, in multi-tenant operation for many customers at once, each with its own
context.

```java
    @Override
    public AdapterInstance bind(AdapterContext context) {
        URI base = context.config().url("home.url");
        AdapterHttp http = context.http();
        http.auth(base, Auth.bearer(context.config().secret("api.token")));
        int pageSize = Math.max(1, context.config().integer("page.size"));
        MyToolReader reader = new MyToolReader(http, base, pageSize);
        return new AdapterInstance() {
            @Override
            public Optional<SourceReader> reader() {
                return Optional.of(reader);
            }

            @Override
            public Optional<TargetWriter> writer() {
                return Optional.empty();
            }
        };
    }
}
```

Duties of every binding:

- All state lives in the `AdapterInstance`, in `store()`, `caches()` or `runStore()`. No static or
  process-wide clients, caches or flags; fields of the `ToolAdapter` class are process-wide too.
- Network traffic goes only through `http()`, own threads only through `executor()`, configuration comes only
  from `config()`. The adapter reads no system properties, no environment variables and no files of its own.
- `close()` releases what the binding built. The host calls it for every binding it discards: after a changed
  setting, when the connection is switched off, and at shutdown. Before that it ends the binding's executor
  tasks: waiting tasks are cancelled, running tasks get 10 seconds and are then interrupted.
- A discarded binding closes only when its last run returns it, so an old and a new binding of one connection
  can live side by side for a while, with the same `bindingId`. Do not assume exclusive resources per
  `bindingId`. It is opaque: good for logs and partitioning, never for authorization.
- A failure in `bind` makes the connection `DEFECT` with its cause and never prevents the host from starting.
  Throw an `AdapterInitialisationException` with a message an administrator understands.

**`checkHealth()`** is called before each run, after a setting of the connection is saved, and on demand. Keep
it light (one `GET`, no content) and within 10 seconds. An `AdapterException` with status 429, 502, 503 or
504, or with `retryable = true` and no status, means "temporarily unreachable"; any other makes the connection
`DEFECT`. Pass the status as a number (`new AdapterException(message, null, retryable, status)`); the host
never reads it from the text.

**Optional members of `AdapterInstance`:** `metadata()` (section 9), `pathHierarchy()` (mandatory with
`PATH_ATTRIBUTE`), and `purge()`, which removes the adapter's own helper objects at the tool, such as webhook
registrations, when a connection is removed for good; never content. A binding may also narrow its type with
`capabilities()`, `connectionKeys()` and `identifierKinds()`; see their Javadoc.

**`parseEvent(headers, body)`** interprets a webhook event in the tool's own format as a `ReadScope`, usually
`ReadScope.ofIds(...)`. The host calls it only after it has authenticated the request. It answers empty ("not
my format"), a scope with explicitly named identifiers (at most 500, each at most 200 characters), or throws
an `AdapterException`. It never returns `null` or a full scope and never sends a request to the tool.

### The HTTP path: `http()`

`context.http()` is the adapter's only way to the network. The host checks every target address, applies the
TLS material of the connection, throttles, retries, enforces deadlines and counts every request. Adapter code
only builds requests and classifies responses; the snippet in section 7 shows both.

- **`send` and `open`.** `send` buffers the response, at most 32 MiB counted after decompression; a larger one
  fails with `ResponseTooLargeException`. `open` returns a `StreamResponse` for large downloads. Always close
  it; closing early aborts the transfer.
- **Requests and bodies.** `AdapterRequest.get`, `head`, `delete`, `post`, `put` or `builder(method, uri)`.
  The host sets the transport headers and `Accept-Encoding`; `Content-Type` comes from the `Body`. Set a
  header that carries credentials with `secretHeader(name, SecretValue)`. `Body.ofBytes`, `ofString` and
  `ofJson` can be read repeatedly; `Body.ofStream(...)` and `Multipart.builder()` are uploads.
- **Responses and failures.** Every HTTP response arrives with its status, including 4xx, 5xx and a 3xx the
  host does not follow. A transport failure arrives as an `AdapterException`, retryable if it is transient.
  Redirects are not followed unless the request asks for it with `redirects(RedirectPolicy.follow(n))`.
- **Timeouts.** `timeout(...)` on the builder, at most 120 seconds: for `send` until the complete response,
  for `open` until the headers and then as idle timeout between two reads. For uploads and streams the limit
  is 300 seconds and works as idle timeout. There is one timeout per request: it covers waiting at the rate
  limit, redirects, retries and fetching a token, and the host cuts it to the rest of the run's deadline.
- **Retries.** The host repeats only what `AdapterRequest.repeatable()` is: idempotent with a re-readable
  body. By default GET, HEAD, PUT, DELETE and OPTIONS are idempotent, POST and PATCH are not; mark a reading
  POST (a search) with `idempotent(true)`. The host retries after 429, after 503 and after a transient
  transport failure: after `Retry-After` (at most 60 seconds), otherwise after 2, 4 and 8 seconds, at most
  three times. Creates and uploads are never repeated. Do not retry yourself.
- **Authentication.** `http.auth(origin, auth)` with `Auth.basic(...)`, `Auth.bearer(...)` or
  `Auth.oauth(provider)`, per origin; `http().cookies()` is the binding's cookie store. For OAuth the host
  keeps and renews the token. `TokenProvider.fetch(AdapterHttp)` receives a view without authentication; never
  use `context.http()` there, and never log a token response: the host masks a token only once `fetch` returns
  it.

Do not build an HTTP client of your own: no `java.net.http`, no sockets, no `HttpURLConnection`, no client
library that opens its own connections, and no system properties for proxy or TLS. The TCK sees a bypass only
on the paths it exercises (case 2). The template therefore also keeps the rule statically with an ArchUnit
test, `TestCsvToolNetworkPathRule`; copy it and change its package.

### The Spring facade: `rmm_adapter_http_spring`

Code that uses `WebClient` or `@HttpExchange` interfaces keeps them. The module `rmm_adapter_http_spring`
belongs in your jar, depends only on `rmm_adapter_api` and `spring-webflux`, and provides
`AdapterHttpConnector`, a `ClientHttpConnector` over `http()`:

```java
WebClient.Builder builder = WebClient.builder();
AdapterHttpConnector connector = AdapterHttpConnector.configure(builder, context.http());
WebClient client = builder.baseUrl(base.toString()).build();
//in AdapterInstance.close(): connector.close();
```

- One connector per binding, closed in `close()`. Build every `WebClient` through `configure`; a
  `WebClient.builder()` without it would connect past the host.
- `configure` installs a filter that carries the current run into the Reactor context when the hook's thread
  subscribes. Let the subscribing thread wait (`block()` or a synchronous `@HttpExchange` method); concurrency
  belongs on `executor().submit`, not in a `flatMap`. If a chain resubscribes on another thread, pass the run
  with `.contextWrite(AdapterHttpConnector.run(run))`.
- The request attribute `AdapterHttpConnector.IDEMPOTENT` marks a reading POST; `AdapterHttpConnector.TIMEOUT`
  sets the timeout at the host. The facade buffers a request body up to 4 MiB; send multipart above that
  through `AdapterHttp` with `Multipart` and a known length.

### Stores, caches and the executor

All three stores hold named collections (`DocumentCollection`) of JSON documents, separated per connection.

| Store | For | Lifetime |
| --- | --- | --- |
| `store()`: `read`, `write`, `delete`, `collection(name)` | Synchronization state: markers, the cursor of a delta feed | Permanent; the administrator does not clear it |
| `caches().collection(name)`, `caches().memory(name, maxEntries)` | Metadata of the tool, anything that can be reloaded | Collections until the administrator clears the caches (the host then binds anew); `memory`, an LRU cache of at most 10,000 entries, as long as the binding lives |
| `runStore().collection(name)` | State between the phases of one run | Only inside an active run; the host removes it at the end of the run |

- Collection names match `[a-z0-9_]{1,40}`; at most 64 collections per store. A document is a JSON object with
  an id of 1 to 512 characters; `_id` at the top level and names that start with `$` or contain a dot are
  reserved. `index(field)` declares a field for `find(field, value)`; `all(afterId, limit)` pages by id.
- Limits: 4 MiB per document, one million documents per connection across the three stores. At a limit
  `store()` and `runStore()` throw `AdapterStoreLimitException` and the run fails, even if you catch it;
  `caches()` only stops storing. Keep in `caches()` only what you can reload from the tool.
- Never keep the state of a run in a field of the instance: two runs can use one binding at the same time.
- `executor().submit(callable)` runs a side task inside the current run or inside a scheduled task; elsewhere
  it throws `IllegalStateException`. At most `parallelism()` tasks of a binding work at the same time (default
  8), and at most 1000 wait. Wait only through the returned `Future`, never through locks, latches or
  sleeping: only then does the host know that the thread waits. A hook waits for all its tasks before it
  returns; nothing keeps working after that.
- `executor().schedule(name, interval, task)` plans a recurring task per binding (name `[a-z0-9_]{1,40}`, at
  most 16 per binding). The task receives a `TaskContext` with `deadline()` and `stopRequested()` and has no
  `runStore()`. Every host instance runs scheduled tasks itself, so write a task that may run several times.
- `context.logger()` writes to the host's log; every line carries the `bindingId`, and secrets the binding
  received from `config()` are masked. `context.metrics()` is only for requests the host does not see: it
  counts everything that goes through `http()` and measures every hook call itself.

## 7. Reading: `SourceReader`

The host reads the declared phases page by page until `lastPage`: first `readArtifacts`, then
`readRelationships`, `readComments`, `readAttachments` (with `openAttachment` per attachment) and
`readElementFeatures`. What a reader collects in the artifact phase it may use in later phases of the same
run; keep it in `runStore()`.

```java
@Override
public SourcePage<ElementArtifact> readArtifacts(SyncRunContext context, ReadScope scope,
                                                 PageCursor cursor) throws AdapterException {
    int offset = cursor == null ? 0 : offsetOf(cursor);
    AdapterResponse response = http.send(AdapterRequest.get(
            pageAddress(context.externalProjectId(), scope, offset)));
    if (!response.successful()) {
        boolean transientFailure = response.status() == 429 || response.status() >= 500;
        throw new AdapterException("Reading items failed with status " + response.status(), null,
                transientFailure, response.status());
    }
    List<ElementArtifact> artifacts = new ArrayList<>();
    List<ElementFailure> failures = new ArrayList<>();
    //offsetOf, pageAddress and convert are helpers of this reader: offsetOf throws an AdapterException
    //for a cursor that is not an offset; convert adds one artifact per row of the tool, or an
    //ElementFailure for an unreadable row, and returns the number of rows
    int read = convert(response.bodyAsString(), artifacts, failures);
    boolean lastPage = read < pageSize;
    return new SourcePage<>(artifacts, failures, read,
            lastPage ? null : new PageCursor(String.valueOf(offset + read)), lastPage);
}
```

- **Cursor.** `cursor == null` is the first page. `nextCursor` is any string (offset, last id, page number);
  it must terminate and must not repeat. `lastPage` is true exactly when `nextCursor` is null. `readCount`
  counts all elements the tool delivered, failed ones included.
- **Tool terms.** Elements carry the identifiers and values of the tool; the `ElementId` is the stable
  external id, and the artifact type is in the field that the mapping names with `artifactTypeField`. Resolve
  tool ids to display values and render the tool's text formats to HTML. Do not filter: the mapping decides
  what Mirador takes over.
- **Failure isolation.** An element that cannot be read belongs in the page as an `ElementFailure`, not as an
  exception; an exception aborts the phase.
- **Full runs must be complete.** A full run compares the scope of the sync point with the ids of all pages
  read: an element, relationship or comment with a reference of your system that no longer comes is deleted or
  marked in Mirador. A truncated page means deletions. Report an unreadable element in `failures` instead of
  leaving it out: the host does not compare a phase with reported failures.
- **Delta** (`scope.isDelta()`): only with `supportsDeltaRead`; deliver only elements created or changed since
  `scope.changedSince()`. If the tool has a deletion feed, name the ids deleted since then in
  `SourcePage.deletedIds`.
- **Named identifiers** (`scope.isExplicit()`): mandatory for every adapter. Deliver exactly the elements in
  `scope.elementIds()`; a superset is allowed, an omission is not. Leave out a named element the tool no
  longer has, without failure: the host treats it as deleted. Never deliver elements outside the project space
  `context.externalProjectId()`.
- **Comments.** The text is in the field `AdapterConventions.COMMENT_BODY_FIELD` (`Body`), the predecessor in
  a thread in `COMMENT_IN_REPLY_TO_FIELD`, the author optionally as `ElementFieldStringUser` in
  `COMMENT_AUTHOR_FIELD`. Deliver a parent comment before its replies. If the tool uses other fields, name
  them in `MetadataProvider.commentFields()`.
- **Complete parents.** On comment and attachment pages, `SourcePage.completeParentIds` names the artifacts
  whose comments or attachments this page delivers completely; for these the host deletes what is missing, in
  every kind of run. Never name an artifact with a read failure or a truncated list.
- **Attachments and images.** An attachment page carries only metadata (`ElementAttachment`); the host fetches
  the bytes it does not have yet through `openAttachment` and closes the `AttachmentContent`. For every
  `<img src="...">` in delivered text the host asks `openInlineImage(context, parentExternalId, reference)`;
  the default is `Optional.empty()` ("not mine"). Check the origin: never load a reference to a foreign server
  with the credentials of your account.

**Runs with pauses and deadlines.** A run works only while it holds one of the host's few work tokens. Between
two hook calls the host can pause the run to give the token to another run.

- **Cursor and clients must survive any pause.** Minutes or hours can pass between two `read...` calls of one
  run. If the tool keeps server-side state behind the cursor that expires (a scroll context, a search
  snapshot), put enough into the `PageCursor` to resume from the last delivered position instead of failing.
  Duplicates do no harm; an omission does. Renew an expired tool session on the next call.
- **The deadline.** Every hook call receives a fresh `SyncRunContext` whose `deadline` counts active time.
  Compare only with the context of the current call (`context.expired(Instant.now())`) and use it to end long
  work in time. The host enforces it anyway: it interrupts the run's thread, and the run fails. The TCK
  expects a hook to end at most 5 seconds after the deadline. Waiting at the host's rate limit and before a
  retry after 429 does not count against the deadline; a rate limit or a sleep in your own code does.
- **Stopping is an interruption.** Pass on `InterruptedException` or keep the flag set. When the host stops a
  run, calls on the context can throw an unchecked exception of the host. Do not catch it and do not turn it
  into an `ElementFailure`: no `catch (RuntimeException)` or `catch (Exception)` around context calls, or
  rethrow everything that is not an `AdapterException`.

## 8. The mapping template and the plugin manifest

Both files are in `src/main/resources/META-INF/` of the template; TCK case 6 checks them.

**`META-INF/rmm-adapter/mapping-template.json`** translates tool terms into Mirador terms. The host reads a
plugin's template only at this path. Every connection gets a copy of the template; after that a run reads only
the copy. The administrator can download, upload and reset the copy, so a new template in a new version of the
plugin reaches existing connections only through a reset. Without a template in the jar every run fails with
"No element mapping".

```json
{
  "artifactTypeField":      "Type",
  "typeMappings":           { "mytoolToRmm": { "Requirement": "System Requirement" } },
  "attributeNameMappings":  { "mytoolToRmm": { "Summary": "Name", "Text": "Description" } },
  "relationshipMappings":   { "mytoolToRmm": { "satisfies": "Satisfies" } },
  "attributeValueMappings": { "mytoolToRmm": { "Status": { "Is compliant": "Compliant" } } }
}
```

- The direction key ends in `ToRmm`; for writing back, the same table is inverted.
- `attributeValueMappings` is optional and meant for attributes with a restricted set of values. A value
  without an entry skips the attribute with a warning in the run.
- `identifiers` is optional and overrides the descriptor's identifier kinds by name:
  `"key": { "attribute": "Key", "pattern": "([A-Z]+-\\d+)", "format": "{id}", "sample": "PROJ-12" }`.
- `crossToolReferences` is optional: rules for tool fields that carry the identifiers of other artifacts. The
  adapter delivers such a field like any other, and `updateArtifact` receives it as an ordinary field.
- Entries whose key starts with `_` are skipped and can carry comments.

**`META-INF/rmm-adapter.json`**, the manifest, is static, readable without running code, and matches the
descriptor:

```json
{
  "typeKey": "MYTOOL", "name": "My Tool", "version": "1.0.0", "vendor": "Example", "apiMajor": 1, "apiMinor": 1,
  "configKeys": [
    { "key": "home.url", "type": "URL", "scope": "BINDING", "required": true },
    { "key": "api.token", "type": "SECRET", "scope": "BINDING", "required": true },
    { "key": "page.size", "type": "INTEGER", "scope": "BINDING", "required": false }
  ],
  "capabilities": { "directions": ["TO_RMM"], "hierarchyModel": "FLAT", "imageReferenceForms": [] }
}
```

`typeKey`, `name` and `version` equal `systemKey`, `displayName` and `adapterVersion`; `apiMajor` and
`apiMinor` equal `apiMajorVersion` and `apiMinorVersion`; `vendor` is not empty. `configKeys` lists the
descriptor's keys in the descriptor's order. `capabilities.imageReferenceForms` lists exactly the descriptor's
forms, each as `{ "kind": ..., "value": ... }` with kind `SCHEME` or `PATH_TEMPLATE`. The manifest names no
path of the mapping template (no `mappingTemplate`, no `legacyMappingId`). The host accepts at most 64 KiB and
refuses unknown or duplicate fields; `_comment` is allowed.

## 9. Writing: `TargetWriter`

An adapter with direction `FROM_RMM` returns a `TargetWriter`. Elements arrive as a completely mapped view:
the tool type as label, values already in tool terms, the external id and the target location set.

| Hook | Called | Returns |
| --- | --- | --- |
| `createArtifact` | Always; parents before children | The id the tool assigned |
| `updateArtifact` | Always; in parallel, so hooks must be thread-safe | `TargetOutcome` with the applied fields |
| `updateArtifactHierarchy`, `deleteArtifact` | Always | Nothing |
| `convertArtifact` | Only with `supportsArtifactTypeChange` | Nothing |
| `createRelationship`, `deleteRelationship` | With `RELATIONSHIPS` in `writes` | The id; nothing |
| `updateRelationship` | Only with `supportsRelationshipUpdate` | The id, possibly a new one |
| `createCommentWithOutcome` or `createComment` | With `COMMENTS` | `TargetOutcome`; the id |
| `createAttachment`, `deleteAttachment` | With `ATTACHMENTS` | The id; nothing |
| `writeFeatureTrees`, `writeVariants`, `writeElementFeatures` | With `VARIABILITY`; the complete state each time | Nothing; one `TargetOutcome` per element |

- **A failure is an exception.** Throw an `AdapterException` for a failed element; it stays without receipt
  and comes again in the next run. Never swallow a failure. Set `retryable = true` for transient tool
  responses: the host repeats only idempotent calls (reading, updating, deleting), never a create and never
  `convertArtifact`, because after a timeout the tool may already have executed the call.
- **Update and delete are idempotent.** The same update twice gives the same receipt. A delete of an element
  the tool no longer has (404) counts as done; everything else (403, 409, 5xx) is thrown. Delete only inside
  the project space of the run.
- **Receipt per field.** `updateArtifact` returns `TargetOutcome.applied(labels)` with the tool labels of the
  fields the tool accepted. A field the tool does not know or does not let you write may be skipped, but then
  it is missing in the receipt, and Mirador treats it as not written. `TargetOutcome.success()` confirms no
  field. Optionally give the reason per skipped field, as in
  `TargetOutcome.applied(written, Map.of("Global ID", "read-only"))`: a short, fixed text, never a value, a
  secret or a tool response.
- **Notes and skipped comments.** `outcome.withNote("...")` attaches a short, fixed English text to the
  element in the run report without failing it. If nothing presentable would remain of a comment, answer
  `TargetOutcome.skipped(reason)` from `createCommentWithOutcome`: the host stores no reference and offers the
  comment again in every later run. Skipping exists only there. `TargetOutcome` can grow: use its accessors
  and factory methods, and do not deconstruct it with a record pattern.
- **Attachments.** `createAttachment` receives the metadata and an `AttachmentContentSource` that delivers the
  bytes fresh on every `open()`; the returned id is the receipt. `deleteAttachment` receives both ids (`id`
  and `parentId`) and is idempotent. Attachment and variability hooks are not called in runs where the phase
  is switched off; that is not a failure.

**Batches.** The default of every batch hook (`createArtifacts`, `updateArtifacts`, `deleteArtifacts`,
`createRelationships`, `createComments` and the others) is a loop over the single hooks. Override one when the
tool has a bulk endpoint or when costs can be shared per batch, and declare the operation in `bulkOperations`;
without the declaration the host keeps calling the single hooks. A batch hook returns exactly one
`TargetOutcome` per element in input order; a failure affects only its element. For `ARTIFACT_CREATE`,
`precedingElementID` of the location is not reliable inside a batch; the order is in `orderIndex`.

**`MetadataProvider`.** If `AdapterInstance.metadata()` returns a `MetadataProvider`, Mirador checks mappings
against the tool as well: `artifactTypes`, `fieldTypes` and `relationshipTypes` per project. An
`ElementFieldType` carries `dataType`, `writable`, `multiValued` and the `options` of a restricted field.
Build the answers per call, not as static values.

## 10. Testing with the TCK

The TCK checks an adapter against the contract without a running Mirador. It is an ordinary JUnit test of your
build (`mvn test`):

```java
public class TestMyToolContract extends AdapterContractTest {

    @Override
    protected ToolAdapter adapter() {
        return new MyToolAdapter();
    }

    @Override
    protected Map<String, String> configuration() {
        return Map.of("home.url", "https://tool.example", "api.token", "tck-token-1");
    }
}
```

These two hooks are enough for the structural tests, which run without a tool. Everything else is opt-in
through further hooks; a test whose hook is missing is skipped, not passed. The complete pattern, with a
`FakeHttpTool` as the tool, is `TestCsvToolAdapterContract` in the template; the Javadoc of
`AdapterContractTest` describes every hook and every test.

| Hook | Enables |
| --- | --- |
| `adapter()` | Mandatory. Return a fresh object per call |
| `configuration()`, `platformConfiguration()` | Values of the connection; values of scope `PLATFORM` and `<name>.max` caps. A platform key in `configuration()` fails, as in the host |
| `liveRunContext()` | Reading, deadline, caches, run store, pause, `purge()`: the run context of a test project or of a `FakeHttpTool` |
| `networkTool()` | Case 2 and the pause: the `FakeHttpTool` that `configuration()` reaches |
| `secondBindingConfiguration()` | Case 1: a second binding with different content |
| `roundTripArtifact()`, `roundTripComment()`, `roundTripRelationshipType()`, `roundTripAttachment()` | The write tests against a test project |
| `clientIdentity()`, `secondBindingClientIdentity()` | Required for an adapter with a `CLIENT_IDENTITY` key |

| Case | Test methods | What it proves |
| --- | --- | --- |
| Structure | `descriptorIsConstantAndBuiltAgainstThisApiVersion`, `configKeysAreDeclaredWithoutDuplicates`, `identifierKindsRoundTrip`, `declaredImageReferenceFormsKeepToTheGrammarOfTheCore`, `aDeclaredRateLimitIsPlausible`, `bindYieldsAnInstanceConsistentWithTheCapabilities`, `theBindingDeclarationOnlyNarrowsItsType`, `bindLeavesTheStoreIntact` | The checks that would make the adapter `DEFECT` in the host: a constant descriptor, valid declarations, a reader and writer for the declared directions, overridden hooks for the declared phases |
| 1 Two bindings | `twoBindingsWorkSideBySideWithoutCrossTalk` | Two bindings of one type read in parallel without cross-talk in elements, log, store or metrics |
| 2 HTTP path | `everyRequestGoesThroughTheNetworkPathOfItsOwnContext`; after every test `noRequestOfTheCaseBypassedTheNetworkPath` | Every request goes through `context.http()` of its own binding and carries no secret of the other binding |
| 3 Process-wide state | `theAdapterClassesKeepNoProcessWideState` | No static field that is not a constant: no non-final statics, mutable static collections, clients or arrays |
| 4 Lifecycle | `closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults`, `aHookEndsSoonAfterTheDeadlineOfItsRun`, `aNewBindingReloadsWhatTheClearedCachesHeld`, `theRunStoreIsEmptyAfterTheRunAndTheNextRunStartsClean` | `close()` leaves no threads and no open `StreamResponse`; a hook ends soon after the run's deadline; a new binding reloads cleared caches; a second run delivers the same as the first |
| 5 Configuration | `configurationComesOnlyFromTheContext`, `pathKeysAreDeclaredForThePlatformOnly` | The adapter reads no system properties and no environment variables; no `PATH` key in scope `BINDING` |
| 6 Plugin unit | `aPluginUnitCarriesAManifestMatchingItsDescriptorAndItsMappingTemplate` | The manifest matches the descriptor, and the mapping template is one the host reads (section 8) |
| 7 `purge()` | `purgeChangesNoContentOfTheTool` | `purge()` changes no content of the tool |
| Pause | `aCursorSurvivesALongPauseBetweenTwoPages` | The cursor continues after the tool's state has expired |
| Events | `parseEventAnswersEmptyOrAnAdapterExceptionWithoutAToolRequest` | `parseEvent` answers within its rules, without a request to the tool |
| Reading | `everyReadPhaseTerminatesWithSaneCursors`, `anExplicitIdScopeDeliversTheNamedElements`, `aDeclaredDeltaReadAcceptsADeltaScope`, `readAttachmentsAnnouncesUsableMetadataAndExactStreams` | Cursors terminate and do not repeat; named identifiers are delivered; attachments announce the exact number of bytes |
| Writing | `roundTripCreatesFindsAndDeletesAnArtifact`, `updateAndDeleteAreIdempotent`, `parallelUpdatesOfOneElementAreSafe`, `aDeclaredPartialUpdateLeavesTheOtherFieldsUntouched`, `declaredArtifactBulkHooksAnswerOneOutcomePerElementInOrder`, `relationshipChangesFollowTheDeclaredUpdatePath`, `commentRoundTripCreatesAndFindsTheCommentAtItsArtifact`, `attachmentRoundTripCreatesFindsAndDeletesTheFile`, `variabilityRoundTripMirrorsTheSelectionOfAnElement`, `metadataDescribesTheToolConsistently` | What is written can be read back and deleted; receipts are given; declared capabilities hold |

- **Case 1** needs a second binding with different content; a different page size alone proves nothing.
- **Case 5** sets the system properties `<key>` and `rmm.adapter.<systemkey>.<key>` to trap values itself.
  Environment variables cannot be set in a running process: set them as the template does, through
  `environmentVariables` of the Surefire plugin, with values that start with `tck-poison` (for `home.url`:
  `HOME_URL` and `RMM_ADAPTER_MYTOOL_HOME_URL`).
- **The pause** fails if the fake tool has registered no state with `FakeHttpTool.onExpiry`. A tool that keeps
  no state between two requests overrides `expireToolState()` explicitly, as the template does.

**Reading a failure.** A violation is a `TckViolation` (an `AssertionError`); its English message names the
violated rule, the values found and, where possible, the remedy. A skipped test is reported by JUnit as
skipped, with the missing hook or capability in the message; a suite in which most tests are skipped has
proven little. The cases print what they looked at, prefixed with `[TCK]`: the number of classes case 3
checked, the threads case 4 did not count, the requests of `purge()` that change something.

**Tests of your own.** `TckAdapterContext` provides the whole context in memory:
`TckAdapterContext.of(descriptor, configuration)` or `TckAdapterContext.builder(descriptor)`, hooks inside
`inRun(run, work)`, and `closeBinding(instance)` at the end. `logLines()`, `storeEntries()` and
`httpRequests()` show what the adapter did. The limits of the TCK: its HTTP path decides and counts retries
like the host but waits only briefly; it does not check target addresses and has no rate limit. The TCK knows
no pauses and never stops a run. A green TCK is the supplier's statement about its own adapter, not a
certification by Mirador.

## 11. Delivering the plugin

`mvn package` builds the jar. The plugin unit is identified by the SHA-256 of the jar's bytes; give the
administrator the jar and its SHA-256.

1. **Upload.** The administrator uploads the jar: the platform administrator in multi-tenant operation, the
   administrator of the installation otherwise. The administration shows the SHA-256 and the manifest without
   running any code of the unit.
2. **Release.** No plugin runs without an explicit release of its unit, per SHA-256, for single tenants or
   all. Every new build is a new unit and needs a new release; until then the last released version keeps
   running.
3. **Restart.** New code loads only when the host starts, and only what a release names.
4. **Connect.** The tenant's administrator creates a connection of the released type and sets its values
   (section 5). The administration shows each connection with its state: `DEFECT` names its cause,
   `NOT_CONFIGURED` the missing key.

What the host checks:

- **On upload:** the jar is at most 50 MiB and an unambiguous archive. Refused are, among others, duplicate
  entry names, names with `..`, a leading `/`, `\` or control characters, Zip64 and encryption. Limits against
  archive bombs: at most 20,000 entries, 64 MiB per entry and 256 MiB in total when unpacked, and a
  compression ratio of at most 200. The manifest must be valid (section 8).
- **Before any class is loaded:** `apiMajor` and `apiMinor` fit the host (section 12).
- **When the type is registered:** the manifest matches the descriptor, as in TCK case 6; a difference makes
  the version `DEFECT` (`MANIFEST_MISMATCH`).
- **After binding:** the instance is consistent with the capabilities, and what a binding declares only
  narrows its type; otherwise the connection is `DEFECT` (`INSTANCE_INCONSISTENT`).

Mirador does not certify adapters: whether a plugin is trustworthy, the administrator decides before the
release.

## 12. Versioning

The contract has a major and a minor version: `AdapterApiVersion.MAJOR` and `AdapterApiVersion.MINOR`,
currently 1 and 1. The SDK version follows them: SDK `1.1.x` is contract major 1, minor 1.

**What is promised.** Inside a major version the contract changes only in a backward-compatible way, and an
adapter compiled against an earlier minor version runs unchanged. No public member is removed or changed; a
new member comes only with a new minor version; an existing type never gets a new abstract member, only
default methods. `rmm_adapter_api/src/test/resources/frozen-api/major-1.txt` lists every public member with
the minor version it came with, and a test of the SDK compares the list with the compiled classes.

**What the adapter states.** The descriptor names `apiMajorVersion` (`AdapterApiVersion.MAJOR`) and
`apiMinorVersion`, the last argument of the constructor with eight arguments; the constructor with seven
arguments sets 0. Write the minor version as a literal number, not as `AdapterApiVersion.MINOR`: the constant
grows when you compile against a newer contract, and descriptor and manifest would drift apart. Raise it only
when the adapter uses something newer. The manifest names the same numbers as `apiMajor` and `apiMinor`.

**What the host does with a mismatch.** A unit with another major version is not loaded. A unit whose
`apiMinor` is higher than the host's minor version is refused before any of its classes is loaded, with a
message that names the contract version it needs, instead of failing later with `NoSuchMethodError`. A lower
`apiMinor` always loads. The host reads a manifest without `apiMinor` as 0; the TCK requires the field.

| Contract | Content |
| --- | --- |
| 1.0 | The contract with bindings: `ToolAdapter.bind(AdapterContext)`, the context with `http()`, `store()`, `caches()`, `runStore()` and `executor()`, `SourceReader`, `TargetWriter` with `TargetOutcome` (receipts, reasons, notes, skipped comments), `MetadataProvider` |
| 1.1 | `ImageReferenceForm` and `AdapterCapabilities.imageReferenceForms` with `withImageReferenceForms(...)`; `ConfigType.CLIENT_IDENTITY`; `AdapterConventions.CONNECT_TIMEOUT_KEY` (`timeout.connect`); `AdapterInstance.identifierKinds()`; `AdapterInitialisationException.Reason` (`UNSPECIFIED`, `PROTOCOL_TOO_OLD`) with `reason()`; `AdapterDescriptor.apiMinorVersion` with the constructor with eight arguments; `AdapterApiVersion.MINOR` |

An adapter that declares image reference forms, a `CLIENT_IDENTITY` key or `timeout.connect` names `apiMinor`
at least 1; TCK case 6 checks it. A host with contract 1.1 also evaluates `capabilities()`, `connectionKeys()`
and `identifierKinds()` of each binding and calls `purge()` and `parseEvent`.

## 13. Writing a remote service

A remote service is an HTTPS service of your own, in any language, that Mirador addresses through a connection
of type `REMOTE` with the remote protocol, version 4. [REMOTE_PROTOCOL.md](REMOTE_PROTOCOL.md) describes every
endpoint, field and rule, and the operation of a service including certificate rotation; this section names
the duties and the tools.

1. **Mutual TLS.** Only `https`, TLS 1.3 or 1.2 with AEAD cipher suites. Refuse every request without a client
   certificate or with one that is not pinned, in the handshake or with 401 or 403. The pinned certificate is
   the one the tenant's administrator downloads from the connection: self-signed, ECDSA P-256, valid for one
   year. Check the pin on every request, not only in the handshake, so that a removed certificate is refused
   on an open connection too.
2. **The binding header.** `X-RMM-Binding` must be present exactly once and equal the binding in the
   certificate's subject alternative name (`URI:urn:rmm:binding:<bindingId>`); otherwise answer 403. The name
   in a certificate authorizes nothing by itself: anyone can issue one. Keep all data separate per binding.
3. **Protocol version 4.** `GET /descriptor` with `protocolVersion` 4, directions, hierarchy model, `reads`
   and `writes`, bulk operations and optionally `connectionKeys` and `identifierKinds`; `GET /health`; reading
   and writing as the protocol describes, with the `deadline` in every call and one result per written
   element. Mirador refuses a service below version 4.
4. **Values of the connection.** Every call with a JSON body carries the values of the keys the service
   declares in `connectionKeys` in the object `connection`. Mirador never sends a `SECRET`; the service keeps
   the access to its tool itself.

**Testing.** Add `rmm_remote_tck` in test scope, extend `RemoteServiceContractTest` and implement
`serviceUrl()` (the `https` address of the running service) and `serviceAnchor()` (the trust anchor for its
certificate, as PEM); `TestRemoteServiceTemplateContract` in the template is the pattern. The service under
test pins the two identities of the TCK (`RemoteServiceIdentities.pinned()` or `pinnedPem()`). The TCK needs
neither Mirador nor a plugin and brings Jackson 3 (`tools.jackson.core:jackson-databind`) into the test scope
of your service. Optional hooks are `externalProjectId()`, `artifactToWrite(text)`,
`commentToWrite(artifactId, text)`, `requestTimeout()` and `connectionValues()`.

Its five tests prove that the service refuses a request without a pinned client certificate on every path,
answers 403 when `X-RMM-Binding` is missing, repeated or names another binding, keeps data and state separate
per binding, serves a version 4 descriptor and `/health`, and follows the outcome rules for written elements,
also in a batch.

**Template.** `rmm_remote_service_template/` is a runnable service in Java 17 with the JDK only: an
`HttpsServer` that requires a client certificate, a directory of pinned certificates, the check on every
request, and one data set in memory per binding. It passes the TCK; its README explains how to copy it.
