# Remote adapter protocol (version 4)

The wire protocol of remote adapters for Wiestigo Mirador: an adapter runs
as a **separate service in any language**, and the host calls it through the
HTTP/JSON calls described here. In content these are exactly the operations
of the adapter contract (`rmm_adapter_api`); the freedom of language is a
feature on top of the standard interface. This document describes version 4
of the protocol.

What the service does NOT implement: mapping, idempotency, batching,
building the hierarchy, change detection, acknowledgement. All of that
stays in Mirador (see `docs/DEVELOPER_GUIDE.md`). The service only
translates between its tool and the wire format.

## Operation and configuration

A remote service is a **connection of type `REMOTE`** of a tenant
(Administration, Connections). Its values:

| Key | Type | Meaning |
| --- | --- | --- |
| `url` | URL, required | the address of the service, always `https://…`; with any other address the connection is `NOT_CONFIGURED` (the message says that a required value is missing and asks for an https address), without a single request |
| `tls.trust` | CERTIFICATE | the trust anchors of the service (PEM); a self-signed certificate of the service is pinned (without checking the name); if the anchor is a certificate authority, the name is checked; empty: the public certificate authorities |
| `tls.client` | CLIENT_IDENTITY, required | the client identity of the connection: Mirador generates the key and the self-signed certificate, they are never a value entered in the administration |
| `timeout.connect` | INTEGER, default 10 | seconds for the descriptor at binding and for the `/health` check (one request each), and also the timeout for establishing the connection of every request of the connection; at most the connect timeout of the operator of Mirador (default 10 s) |
| `timeout.request` | INTEGER, default 120 | seconds per other call (one page, one batch), at least 1 |

The operator of the service installs the **certificate of the connection**
(download in the administration, SHA-256 fingerprint) at the service. Its
subject alternative name `URI:urn:rmm:binding:<bindingId>` names the
binding; the service keeps data and state apart per binding by it. There is
no token: the client certificate is the credential.

### Certificate rotation

The certificate of the connection is valid for one year; the administration
renews it. For the operator of the service this means:

1. **Accept both.** After the renewal there is a prepared certificate next
   to the active one, for **the same binding** (the same subject alternative
   name `URI:urn:rmm:binding:<bindingId>`, a different fingerprint). Install
   it in addition (download in the administration, `?generationId=` of the
   prepared generation): during the rotation the service MUST accept both
   for this binding.
2. **The host probes first.** Before the switch the host binds once with the
   new certificate (descriptor and `/health`). If the service does not
   accept it, nothing changes: the administration reports `PROBE_FAILED`,
   and the old certificate stays active.
3. **Remove the old one only afterwards.** If the probe succeeds, the new
   certificate becomes active and the old one is retired (`RETIRED`). Remove
   the old one from your service only when the administration shows it as
   retired; before that a running synchronization may still present it.

## Transport

- JSON over **https**, UTF-8, TLS 1.3 or 1.2 with AEAD cipher suites. The
  host connects only over https and identifies itself in every handshake
  with the client certificate of the connection; without a usable identity
  (none, expired) it sends nothing. The service MUST require a client
  certificate and accept only the installed ones. An address with `http://`
  does not bind: the connection is `NOT_CONFIGURED`, the message names
  `url` and `https`, and no request goes out until the address is changed.
- Every request carries `X-RMM-Adapter-Protocol: 4` and
  `X-RMM-Binding: <bindingId>`, no `Authorization` and no identifier of the
  connection (the service never learns the name the connection has in
  Mirador). The service MUST answer `403` when the header is missing, is
  present more than once or names a different binding than the certificate
  (`URI:urn:rmm:binding:<bindingId>`); the host then holds the connection
  as `DEFECT` with the status. The header alone authorizes nothing; it only
  assigns the request to a binding. The host never follows redirects. The
  binary channel of the attachments (below) is the exception to JSON: there
  bytes travel as a stream or as a multipart part.
- **Deleting is idempotent:** on `/write/artifact/delete`,
  `/write/relationship/delete` and `/write/attachment/delete` a `404`
  counts as DONE: the element no longer exists, and exactly that was the
  goal. A service may therefore answer 404 or 2xx; both are correct. (For
  the batch deletes the outcome per element decides, not the status of the
  call.)
- **Error semantics:** every non-2xx answer is a failure of the CALL; the
  response body (recommended: `{"message": "..."}`) appears as the cause in
  the Mirador run. Errors of SINGLE elements belong in the `failures` of
  the page instead; they do not abort the run.
- TLS material per connection: the trust anchors from `tls.trust`, the
  client certificate from `tls.client`. A failed handshake is final.
- Timeouts of the connection: `timeout.connect` (the descriptor at binding
  and `/health`, default 10 s; also the time within which the host's HTTP
  path establishes every single TCP connection, at most the connect timeout
  of the operator of Mirador) and `timeout.request` (per call, that is one
  page or one batch, default 120 s, at least 1 s), in seconds. The request
  timeout applies to the whole call, header and body, across all attempts;
  the host caps it with its own response timeout (an operator setting,
  default 120 s). Exceeding it counts as transient in the run and is never
  retried: after it no time is left for another attempt. A response body
  above the host's maximum size (an operator setting, default 32 MiB) is
  aborted at the first byte beyond it and is a final error of the call (the
  same page would come back just as large); a page carries at most 500
  artifacts, so the limit is far away.
- **Reporting overload:** if the service or the tool behind it is
  overloaded or briefly unavailable, answer `503` or `429`, preferably with
  `Retry-After` (seconds or an HTTP date; at most 60 s are honored). The
  host retries only these two statuses (see `rateLimit`); `502` and `504`
  count as transient in the run but are not retried.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/descriptor` | self-description (read once each time the connection is bound) |
| GET | `/health` | 2xx = healthy. Otherwise the host classifies by the HTTP status, never by the body: 429, 502, 503, 504 and unreachable = transient (connection `READY` with a notice); 401/403 = "credentials rejected", which means the client certificate or the binding header; any other status = `DEFECT` with the status; a certificate error in the TLS handshake (expired, wrong chain, wrong name) = `DEFECT` "TLS certificate"; any other failed handshake (only older protocol versions or cipher suites, no TLS on the port, client certificate rejected; the last one only if the service reports the rejection with a TLS alert: the JDK `HttpsServer` as used in `rmm_remote_service_template` does so only from Java 25, before that it closes without an alert and the host sees an aborted connection as described next) is final on the host's HTTP path and results in `DEFECT` without detail; a handshake that is too slow or fails because the connection is aborted, and a TLS error after the handshake, are transient like "unreachable". The check is exactly one request, not repeated even after 429, 503 or a connection error. This classification applies only to the state of the connection; in a run the host retries transient connection errors within the `rateLimit`, a TLS error never. Never report a problem of the tool **behind** the service as 401/403 (that would say the host's certificate is wrong): tool overloaded or briefly unavailable as 503 (or 429, with `Retry-After`), unreachable as 503 or 502, a permanent rejection (such as the service's own access to the tool) as 500 with the reason in the body. The check runs before every run, after saving the connection and on demand in the administration, never periodically |
| GET | `/metadata?externalProjectId=…` | descriptors of artifact types, fields and relationship types (optional; 404 = no metadata) |
| POST | `/read/artifacts` | page of artifacts |
| POST | `/read/relationships` | page of relationships |
| POST | `/read/comments` | page of comments |
| POST | `/write/artifact/create` | create an artifact; answers the assigned ID |
| POST | `/write/artifact/update` | transfer attributes |
| POST | `/write/artifact/update-hierarchy` | transfer the position |
| POST | `/write/artifact/convert` | transfer type AND fields (only with `supportsArtifactTypeChange`) |
| POST | `/write/artifact/delete` | delete an artifact |
| POST | `/write/relationship/create` | create a link; answers the assigned ID |
| POST | `/write/relationship/update` | change a link (only with `supportsRelationshipUpdate`) |
| POST | `/write/relationship/delete` | delete a link |
| POST | `/write/comment/create` | create a comment; answers the assigned ID, or skipped with a reason |
| POST | `/read/attachments` | page of attachments: metadata, no bytes (only if `reads` contains `ATTACHMENTS`) |
| GET | `/read/attachments/{id}/content[?deadline=…]` | bytes of one attachment as a stream |
| POST | `/write/attachment/create` | create an attachment, multipart with a JSON part and the file; answers the assigned ID (only if `writes` contains `ATTACHMENTS`) |
| POST | `/write/attachment/delete` | delete an attachment, idempotent |
| GET | `/read/inline-image?parent=…&reference=…[&deadline=…]` | bytes of an image embedded in rich text; `204` means "does not belong to this service" |
| POST | `/read/element-features` | page of selections: the feature selection per element (only if `reads` contains `VARIABILITY`) |
| POST | `/write/feature-trees` | the visible feature trees as a whole (only if `writes` contains `VARIABILITY`) |
| POST | `/write/variants` | the visible variants as a whole |
| POST | `/write/element-features` | batch: the selection per element; answers one outcome per element |
| POST | `/write/artifacts/create` | batch: create artifacts (only with `ARTIFACT_CREATE`) |
| POST | `/write/artifacts/update` | batch: transfer attributes (`ARTIFACT_UPDATE`) |
| POST | `/write/artifacts/update-hierarchy` | batch: transfer positions (`ARTIFACT_HIERARCHY_UPDATE`) |
| POST | `/write/artifacts/delete` | batch: delete artifacts (`ARTIFACT_DELETE`) |
| POST | `/write/relationships/create` | batch: create links (`RELATIONSHIP_CREATE`) |
| POST | `/write/relationships/update` | batch: change links (`RELATIONSHIP_UPDATE`) |
| POST | `/write/relationships/delete` | batch: delete links (`RELATIONSHIP_DELETE`) |
| POST | `/write/comments/create` | batch: create comments (`COMMENT_CREATE`) |

Only declared directions are called: without `FROM_RMM` never a
`/write/*`, without `TO_RMM` never a `/read/*`. Without
`supportsRelationshipUpdate` the host orchestrates link changes itself as
delete plus create.

## `GET /descriptor`

```json
{
  "systemKey": "POLARION",
  "displayName": "Polarion ALM",
  "adapterVersion": "1.2.0",
  "protocolVersion": 4,
  "capabilities": {
    "directions": ["TO_RMM", "FROM_RMM"],
    "hierarchyModel": "NATIVE_TREE",
    "supportsRelationshipUpdate": false,
    "supportsArtifactTypeChange": false,
    "supportsPartialUpdate": true,
    "supportsDeltaRead": true,
    "reads": ["ARTIFACTS", "RELATIONSHIPS", "COMMENTS", "ATTACHMENTS", "VARIABILITY"],
    "writes": ["ARTIFACTS", "RELATIONSHIPS", "COMMENTS", "ATTACHMENTS", "VARIABILITY"],
    "bulkOperations": [],
    "rateLimit": { "maxRequestsPerSecond": 10, "maxRetries": 3 }
  },
  "pathHierarchy": { "attributeName": "folderPath", "attributeLabel": "Folder", "separator": "/" },
  "identifierKinds": [
    { "name": "workItemId", "fieldLabel": "ID", "pattern": "([A-Z]+-\\d+)", "format": "{id}",
      "sample": "REQ-123", "description": "Polarion work item ID, e.g. REQ-123" }
  ],
  "connectionKeys": [
    { "key": "project.filter", "type": "STRING", "required": false, "default": "",
      "description": "Only work items of this project" }
  ]
}
```

**Per binding:** the host fetches the descriptor every time the connection
is bound, once, without a retry, over the HTTP path of the connection (with
its client certificate), and keeps it until the next binding. The
capabilities and keys apply to exactly this connection. The connection type
`REMOTE` declares the upper bound (both directions, `NATIVE_TREE`, every
`supports…` capability, every phase and bulk operation, no rate limit of
its own), and the service may only **narrow** it: a hierarchy model at most
as rich as that of the type (`NATIVE_TREE` before `PATH_ATTRIBUTE` before
`FLAT`), subsets of the directions, phases and bulk operations, a
`rateLimit` of its own. The wire protocol has no image reference forms.

`connectionKeys` is OPTIONAL: further values that the tenant maintains on
this connection. The host sends them to the service in every envelope with
a JSON body as the object `connection`, never in a `GET` (see "Read
calls"). The administration accepts a `SECRET` and stores it sealed, but
the host never sends it; a service therefore better declares none. Per
entry:

- `key`: `[a-z0-9.-]{1,64}`; not starting with `rmm.`; not already a key of
  the connection type (`url`, `tls.trust`, `tls.client`, `timeout.connect`,
  `timeout.request`) or a key that Mirador keeps on every connection
  (`webhook.secret`, `webhook.replay-window-seconds`,
  `webhook.debounce-seconds`, `project-spaces`, `conversion.delete-create`,
  `attachments.enabled`, `variability.enabled`, `images.enabled`,
  `orphaned-elements`, `orphaned-elements.propagate`,
  `orphaned-elements.max-delete-percent`); not `http.rate.per.second`,
  `http.retries` or `<name>.max`; each key only once.
- `type`: `STRING`, `INTEGER`, `BOOLEAN` or `SECRET`. The key of a `SECRET`
  ends in a secret name (`password`, `passwd`, `secret`, `token`,
  `credential(s)`, `apikey` or `api.key`) and has no default, and only a
  `SECRET` may be named like that.
- `required`, `default`.
- `description`: required, at most 500 characters.
- `scope`: optional, only `BINDING`.

At most 64 entries. `CERTIFICATE`, `CLIENT_IDENTITY`, `PATH` and `URL` are
declared only by the connection type. A violation of these rules, or a
descriptor that widens the capabilities, puts the connection in
`DEFECT INSTANCE_INCONSISTENT`.

The host reads the identifier kinds (`identifierKinds`) per binding; in
Mirador they apply to exactly this connection (the reference rules of the
connection use them; the connection type `REMOTE` declares none).

`identifierKinds` is OPTIONAL: the identifier kinds of the tool, by which
people or the tool itself name artifacts in reference fields (cross-tool
references). Per entry `name` (required; letters, digits, `-` and `_`,
starting with a letter; not `rmmId`/`externalId`; each name at most once,
otherwise `DEFECT INSTANCE_INCONSISTENT`), `fieldLabel` (the tool field
that holds the identifier; if it is missing, the external ID is meant),
`pattern` (regex, capture group 1 = identifier; if it is missing, the whole
value), `format` (template with `{id}`, default `{id}`), `sample` (a sample
identifier that the pattern reads; required as soon as the pattern does not
read `ABC-123`), `description`. At most 32 entries, `pattern` at most 256
characters, every other field at most 500 (the pattern runs in the host
over the values of the artifacts); otherwise
`DEFECT INSTANCE_INCONSISTENT`. The pattern must read back what the format
writes (round trip with the sample; the TCK checks it in case 4). The host
applies a pattern of the service only to the first 4096 characters of a
value and gives it at most 100 ms per value; when the time runs out, the
value counts as not matching, and the host writes one WARN log line per
connection, identifier kind and pattern (without the value). A pattern
without nested repetitions stays far below that. The mapping file of the
tool (`identifiers`) may override every default.

`systemKey`, `displayName` and `adapterVersion` are REQUIRED (non-empty
texts); `systemKey` names the service (for display), the host does not
match it against anything; `hierarchyModel` is one of `NATIVE_TREE`,
`PATH_ATTRIBUTE`, `FLAT`; `rateLimit` and `bulkOperations` are optional. A
`rateLimit` allows at least one request per second (`maxRequestsPerSecond`
at least 1, `maxRetries` at least 0); every bulk operation requires its
phase in `writes` (`ARTIFACT_*` requires `ARTIFACTS`, `RELATIONSHIP_*`
requires `RELATIONSHIPS`, `COMMENT_CREATE` requires `COMMENTS`), and
`supportsRelationshipUpdate` likewise requires `RELATIONSHIPS`; missing
boolean capabilities count as `false`.

`reads` and `writes` are REQUIRED (also as an empty array): the element
phases that the service reads in the direction from the tool to Mirador,
or writes in the direction from Mirador to the tool: `ARTIFACTS`,
`RELATIONSHIPS`, `COMMENTS`, `ATTACHMENTS`, `VARIABILITY`; with the
respective direction at least `ARTIFACTS`, without the direction empty.
The host calls `/read/relationships`, `/read/comments`,
`/read/attachments*`, `/read/element-features`, `/write/relationships/*`,
`/write/comments/*`, `/write/attachment/*`, `/write/feature-trees`,
`/write/variants` and `/write/element-features` only for declared phases;
a descriptor without the two fields is rejected, so that a service never
silently falls back to "artifacts only".

`pathHierarchy` is REQUIRED with `PATH_ATTRIBUTE` (otherwise the host
refuses to bind); an object that is sent names all three fields as
non-empty texts in every other case too, otherwise the connection does not
bind. The service configures itself, which is why its path attribute
travels with the descriptor instead of through the configuration of the
host.

The host enforces `rateLimit`: it keeps the interval between the calls to
the service and retries answers with status 429 and 503 as well as
transient connection errors up to `maxRetries` times (at most 3), after the
`Retry-After` of the answer (at most 60 s), without that header after 2, 4
and 8 s, for read, update and delete calls. A `/write/*/create`, a type
change (`/write/artifact/convert`), writing the variability
(`/write/feature-trees`, `/write/variants`, `/write/element-features`), an
upload and `/health` are never retried (the service might already have
executed the call). `502` and `504`, a TLS error and an exceeded request
timeout are never retried; `502`/`504` count as transient in the run, a TLS
error as final. Every other error status is final. The semantics of all
capabilities are described in `docs/DEVELOPER_GUIDE.md`.

`bulkOperations` declares exactly the batch endpoints the service offers
(see "Batch calls"): the host calls `/write/artifacts/create` only with
`ARTIFACT_CREATE` and so on; without a declaration every write call travels
on its own. A declaration without the endpoint shows up in the TCK (case 5
calls the declared `ARTIFACT_CREATE`, `ARTIFACT_UPDATE` and
`COMMENT_CREATE`) and in operation as a failure of the whole batch.

## Read calls (`POST /read/*`)

Request (the same for all three phases):

```json
{
  "connection": { "project.filter": "PRJ-1-A" },
  "runId": "0b8f3c1e-…",
  "externalProjectId": "PRJ-1",
  "changedSince": "2026-08-20T06:00:00Z",
  "elementIds": null,
  "cursor": "50",
  "deadline": "2026-08-20T07:00:00Z"
}
```

- `connection`: the values of the keys that the descriptor of this binding
  declares under `connectionKeys`: per key the value of the connection or
  its default, `STRING` as text, `INTEGER` as a number, `BOOLEAN` as a
  boolean; the host never sends a `SECRET`. The object is part of every
  envelope with a JSON body (every `POST /read/*` and `/write/*`, also in a
  batch and in the JSON part of an attachment), empty (`{}`) without keys;
  the calls over `GET` without a body (descriptor, `/health`, metadata, the
  bytes of an attachment, embedded images) do not carry it. A key can be
  missing in the object as long as the host does not yet know the keys of
  the binding (the calls directly after the first binding of a connection)
  or when its value does not fit its type; a service then uses its default.
  When a value changes, the host binds the connection again.

- `deadline`: the deadline of the run as a point in time (ISO-8601); if the
  field is missing, the run has no deadline. The host passes it anew with
  every page or call (pauses and waiting time at the rate limit push it
  back). After the deadline the service may abort its work on this call; it
  is enforced by the host anyway, which sends no further request for the
  run after it. The two read paths over `GET` without a body
  (`/read/attachments/{id}/content`, `/read/inline-image`) carry it as a
  query parameter: `?deadline=…` or `&deadline=…` (URL-encoded), likewise
  only when the run has a deadline.

- `changedSince`: `null` = full run. A value (ISO-8601) comes only with
  `supportsDeltaRead`; then return only elements that have changed since.
- `elementIds`: `null` or a list of identifiers: then return exactly these
  elements. This needs no capability; every service supports it. Never
  together with `changedSince`. A named identifier that the tool no longer
  has is left out without a `failure`; the host treats its absence as a
  deletion.
- `cursor`: `null` = first page; otherwise the `nextCursor` of the previous
  page. The value is an **opaque string of the service** (offset, last ID,
  page number, whatever the tool API offers). It must terminate and must
  not repeat.

Response:

```json
{
  "elements": [ …elements, see below… ],
  "failures": [ { "externalId": "SR-9", "message": "unreadable row" } ],
  "readCount": 2,
  "nextCursor": "100",
  "lastPage": false,
  "deletedIds": [],
  "completeParentIds": []
}
```

- `deletedIds` (optional): in a delta run the identifiers deleted in the
  tool since `changedSince`, on every page or collected on the last one;
  the host deletes or marks exactly these elements. If the field is missing
  or empty, only a full run detects deletions.
- `completeParentIds` (optional, only `/read/comments`): the artifact
  identifiers whose comments are delivered COMPLETELY with this page. For
  exactly these artifacts the host compares its comments that carry a
  reference of the system against the delivered ones and deletes what is
  missing, also in a delta run. An artifact with a read error or a
  truncated comment list must not be named. If the field is missing or
  empty, the service makes no statement.

Invariants (a page that violates them is a protocol error of the service):
`lastPage=true` if and only if `nextCursor=null`; `readCount` is at least
the number of `elements` (what counts is what was READ, including what
failed). Ordering rule for comments: thread parents before their replies.

## Write calls (`POST /write/*`)

Envelope of all write calls:

```json
{ "connection": { … }, "runId": "…", "externalProjectId": "PRJ-1", "deadline": "2026-08-20T07:00:00Z",
  "artifact": { … } }
```

(`relationship` and `comment` analogously; the delete calls carry
`"externalId": "SR-7"` instead of the element. `connection` and `deadline`
as in the read calls, also in a batch and in the JSON part of an
attachment.) The elements arrive as a **fully mapped view**: the tool type
as `label`, the field values already translated into the terms of the tool.
Response of the create calls:

```json
{ "externalId": "WI-4711" }
```

The assigned ID becomes the external reference of the element in Mirador;
it must show up again as `id` at the next `/read/artifacts`. The response
of `/write/artifact/update` is the **receipt**: the tool's names of the
fields that the tool accepted:

```json
{ "appliedFields": ["Name"],
  "notApplied": { "Trace Reference": "read-only in the tool" },
  "notes": ["two images without a web address were dropped"] }
```

A field that the service skips is missing in `appliedFields`; the host
treats it as not written. Without the array every field counts as
unconfirmed. Names that the host did not send are ignored in
`appliedFields` as in `notApplied` (no violation). The other update and
delete calls answer with 2xx and any body (also an empty one).

**Outcome per element.** The response of `/write/artifact/update`, that of
`/write/comment/create` and every outcome of a batch carry the same fields
as `TargetOutcome` in the adapter contract (`rmm_adapter_api`):

| Field | Meaning |
| --- | --- |
| `externalId` | the assigned identifier (create, also in a batch); it also counts next to a receipt |
| `appliedFields` | the receipt (update): the tool's names of the accepted fields |
| `notApplied` | optional, `{ "field": "reason" }`: why a field that was sent is not written (short, fixed text for the operator); a field is never in `appliedFields` at the same time |
| `notes` | optional, notes for the operator, each a non-empty text, for example "two images without a web address were dropped"; not an error, Mirador shows them at the element in the run report |
| `skipped` | optional, `{ "reason": "…" }`: deliberately not written and not failed, for example a comment of which nothing presentable would remain in the tool. Then without `externalId` and without a receipt; the reason becomes the first note, the host does not acknowledge the element and presents it again at the next run. Only when creating a comment; on every other call the host fails the element. `"skipped": false` or `null` means not skipped |
| `error` | this element has failed, with a reason; everything else in the outcome then does not count. In a batch exactly this element fails, in the response of `/write/artifact/update` and `/write/comment/create` the call fails, even with status 2xx (otherwise single calls fail with a non-2xx) |

```json
{ "skipped": { "reason": "the comment has no text the tool can show" } }
```

A response that contradicts this (`skipped` without a reason or with an
identifier, an empty note, a reason without text, a field both acknowledged
and not written) is a protocol error of the service: a single call fails,
in a batch exactly this element fails. A response of
`/write/comment/create` without `externalId` and without `skipped` fails
the comment. The host does not read notes on the other single calls
(creating artifacts, links and attachments, deleting): the contract knows
only the identifier there; a service that wants to give notes declares the
bulk operation and answers in the batch.

## Batch calls (`POST /write/<plural>/<operation>`)

Only for operations that the descriptor names in `bulkOperations`. Instead
of one element the envelope carries an array: `artifacts`, `relationships`,
`comments`, or `externalIds` for delete:

```json
{ "connection": { … }, "runId": "…", "externalProjectId": "PRJ-1", "artifacts": [ { … }, { … } ] }
```

Response: **exactly one outcome per element, in input order**. An outcome
is `{ "externalId": "WI-4711" }` (create), `{ "appliedFields": [ … ] }`
(update, the receipt; also for `ARTIFACT_UPDATE`: without it every field of
the element counts as unconfirmed, and the TCK rejects the service), `{}`
(delete, hierarchy) or `{ "error": "reason" }`; then exactly this element
fails, and the others count as answered. In addition every outcome can
carry `notApplied`, `notes` and (only `/write/comments/create`) `skipped`
as above; an outcome that contradicts the contract fails only its element:

```json
{ "outcomes": [ { "externalId": "WI-4711", "notes": ["mentions became plain text"] },
                { "error": "summary is required" },
                { "appliedFields": ["Name"], "notApplied": { "Trace Reference": "read-only in the tool" } } ] }
```

If the number does not match the input, the host treats the whole batch as
a contract violation (every element unacknowledged). A non-2xx of the whole
call leaves every element unacknowledged; on status code 429 or 503 the
host retries update and delete batches within the `rateLimit`, create
batches never; it retries no batch on `502`/`504`. Batch calls may take
longer: `timeout.request` of the connection applies per call.

## Attachments

Attachments travel in two channels: the **metadata** as a page like every
other phase, the **bytes** per attachment as a stream, never as JSON. Only
for services that declare `ATTACHMENTS`.

`POST /read/attachments` takes the same request as the other read calls and
answers with a page whose elements look like this:

```json
{
  "id": "ATT-4711",
  "parentId": "SR-1",
  "fileName": "brake.pdf",
  "contentType": "application/pdf",
  "byteCount": 48213,
  "contentHash": "3a7bd3…",
  "modifiedAt": "2026-09-10T08:15:00Z",
  "description": "Measurement report",
  "fields": []
}
```

- `parentId` (required): the artifact in the tool that the attachment
  belongs to.
- `fileName` (required), `contentType` (recommended), `byteCount` (`-1` or
  omitted = unknown), `contentHash` (SHA-256 hexadecimal, `null` without
  one), `modifiedAt` (ISO-8601, `null` without one), `description`
  (optional).
- `completeParentIds` (optional, as for `/read/comments`): the artifact
  identifiers whose set of attachments is delivered COMPLETELY with this
  page; the host removes from its data set what is missing on them.
- `deletedIds` (optional) as for the other phases.

The host downloads bytes only for what is unknown or changed: the same
hash, or without a hash the same length and a `modifiedAt` not after the
last synchronization, means no download (without hash and timestamp the
length decides).

`GET /read/attachments/{id}/content` returns the bytes of the attachment
with the identifier `{id}` (URL-encoded). Rules of the binary channel:

- Response `200` with the bytes in the body, the `Content-Type` of the
  attachment and **`Content-Length` (required)**: without an announced
  length the host rejects the stream, because its attachment store accepts
  no upload of unknown length and the size limit is applied to the header
  already. A stream that delivers more than announced is aborted.
- The limit is Mirador's attachment limit (an operator setting, default
  1 GiB), not the maximum size of the JSON responses; a larger announcement
  is a final error of the element. The request timeout applies until the
  response header (capped by the host's limit for streams, an operator
  setting), after that as an idle timeout between two chunks; the bytes
  flow directly into the attachment store.
- `404` = the attachment does not exist (any more); every other non-2xx as
  for the JSON calls. The call is retried on 429, 503 and transient
  connection errors within the `rateLimit`, not on `502`/`504`.

`GET /read/inline-image?parent={externalId}&reference={reference}` (both
URL-encoded) returns the bytes of an image that sits IN THE MIDDLE OF THE
RICH TEXT of an element. The reference is the string that the service
itself wrote into the delivered text: an address of its tool, a file name,
a scheme of its own.

- Response `200` as for the attachment download: bytes, `Content-Type` and
  **`Content-Length` (required)**.
- Response **`204` without a body** means "this reference is not mine". The
  host then leaves the place in the text unchanged: an image from a public
  server is no matter of the synchronization. A service that keeps no
  embedded images at all always answers `204`.
- The size limit is that of Mirador's image store (an operator setting,
  default 25 MB); whatever is larger stays in the text as the service
  delivered it and is counted in the run report.
- **Load only your own origin.** The reference comes from the text of an
  external system; a service that fetches it unchecked turns itself into a
  messenger for arbitrary addresses. Check scheme, host and port against
  your configured tool address and answer `204` otherwise.

`POST /write/attachment/create` is `multipart/form-data` with exactly two
parts, in this order:

1. `request` (`application/json`): the envelope as in every write call,
   with `"attachment": { … }` in the form of the read elements (`parentId`
   = the item of the artifact in the tool, `fileName`, `contentType`,
   `byteCount`, `contentHash`, `modifiedAt`, `description`; the `id` is the
   Mirador identifier and only informative).
2. `file` (`Content-Type` of the attachment, `filename` = `fileName`): the
   bytes, as a stream. The call carries its total length (`Content-Length`:
   JSON part, file and boundaries, not chunked); the length of the file is
   also in the JSON part.

Response `{ "externalId": "ATT-4711" }`; the assigned identifier must come
back as `id` at the next `/read/attachments`. A create call is never
retried, and the file part travels exactly once: the host never sends the
call again with half a file. For large attachments `timeout.request` of the
connection applies as an idle timeout (while sending the file, from its end
to the response header, and between two chunks of the response), bounded by
the host's maximum duration of a stream; if the transfer stands still for
longer, the host closes the connection.
`POST /write/attachment/delete` carries `"externalId"` like the other
delete calls, plus `"parentId"`: the identifier of the element that the
attachment belongs to; some services remove an attachment only through its
element (a tool such as Jama removes the LINK between item and attachment).
The call is **idempotent**: an identifier that does not exist (any more) is
done; 2xx or 404, both count as done. The host calls deletions before
creations; an attachment replaced in Mirador is both.

## Variability

Only for services that declare `VARIABILITY`. Feature trees and variants
flow only from Mirador to the tool: the tool mirrors, Mirador leads. Every
run sends the complete state; a tree that the list no longer names is
deleted in Mirador. The selection per element flows in both declared
directions. All names are qualified: `TREE.FEATURE`.

`POST /write/feature-trees`: envelope as in every write call, plus:

```json
{
  "featureTrees": [{
    "name": "CONTROL_UNIT_X", "label": "Control unit X", "description": null, "global": false,
    "features": [
      { "name": "PWR", "label": "Power", "description": null, "kind": "MANDATORY", "parentName": null },
      { "name": "PWR_HIGH", "label": "high", "description": null, "kind": "ALTERNATIVE", "parentName": "PWR" }
    ],
    "constraints": [
      { "kind": "REQUIRES", "feature": "CONTROL_UNIT_X.DIAG_LTE", "otherFeature": "CONTROL_UNIT_X.PWR_HIGH" }
    ]
  }]
}
```

- `kind` of a feature: `MANDATORY`, `OPTIONAL`, `ALTERNATIVE`, `OR`
  (relative to the parent node); `parentName` is the name of the parent
  feature in the same tree, `null` on the top level; the order is the
  document order.
- `kind` of a constraint: `REQUIRES` or `EXCLUDES`; both sides qualified,
  the other one may lie in a global tree.
- Response: `2xx` with any JSON body (also an empty one).

`POST /write/variants`: `{ "variants": [{ "name", "code", "description", "global", "valid", "selectedFeatures": ["TREE.FEATURE", …] }] }`;
`valid` is Mirador's validity verdict. Response as above.

`POST /write/element-features`: `{ "elements": [{ "externalId": "SR-1", "featureNames": ["MARKETS.EU_ONLY"] }] }`
(`featureNames` can consist of **one** entry that is a condition
expression, `["MARKETS.EU_ONLY AND NOT CONTROL_UNIT_X.PWR_HIGH"]`; a
service that returns it unchanged gets it stored as an expression); an
empty list means "no selection" and must be stored. Response as for the
batch calls: `{ "outcomes": [ {}, { "error": "…" } ] }`, exactly one
outcome per element, in order.

`POST /read/element-features` takes the same request as the other read
calls (full run, `changedSince` or `elementIds`) and answers with a page
whose elements are `{ "externalId": "SR-1", "featureNames": [ … ] }`. The
host does not reject unknown names: they are counted as a finding of the
run and not stored, the other names apply.

## Metadata (`GET /metadata`)

Optional. Response:

```json
{ "artifactTypes": [ { "id": "req", "name": "req", "label": "Requirement" } ],
  "fieldTypes": [ { "id": "trace", "name": "trace", "label": "Trace Reference",
                    "dataType": "STRING", "writable": false, "multiValued": false, "options": [] } ],
  "relationshipTypes": [ { "id": "rel", "name": "rel", "label": "Relation",
                           "labelInward": "is related to", "labelOutward": "relates to" } ] }
```

`dataType` names a field type of the table below (if it is missing, the
type is unknown), `writable` (default `true`) says whether the service sets
the field through `/write/artifact/update`, `options` are the values of a
field with a restricted set of values. Mirador's mapping validation uses
this to report reference rules that target unknown, read-only or unsuitable
fields.

## Elements

**Artifact:**

```json
{
  "id": "SR-1",
  "name": "Requirement",
  "label": "Requirement",
  "location": { "parentId": "PRJ-1", "parentIsRoot": true, "orderIndex": 0, "precedingElementId": null },
  "fields": [ …fields… ]
}
```

`location` is optional and only relevant with `NATIVE_TREE` (reading: the
position in the tool tree; writing: the target position).

**Relationship:** additionally `"fromItemId"`, `"toItemId"` (external IDs
of the endpoints). **Comment:** additionally `"commentToItemId"`; text and
thread reference travel as fields with the conventional names `Body`,
`InReplyTo`, optionally `Author` (type `STRING_USER`).

## Fields

```json
{ "name": "priority", "label": "Priority", "type": "STRING", "value": "high", "required": false }
```

`type` and the form of `value`:

| type | value |
| --- | --- |
| `STRING`, `HTML`, `JSON` | string |
| `INTEGER`, `LONG`, `BIG_INTEGER`, `BIG_DECIMAL`, `DOUBLE`, `BOOLEAN` | string in the usual text form (e.g. `"42"`, `"true"`) |
| `DATE` | string `yyyy-MM-dd'T'HH:mm:ss.SSSZ` (e.g. `"2026-08-21T10:15:00.000+0000"`) |
| `LIST_STRING` | JSON array of strings |
| `STRING_USER` | object `{ "userName": "...", "firstName": "...", "lastName": "..." }` |

`value: null` means: the field is present, its value is empty. The host
rejects unknown `type` values; the protocol knows exactly this list.

## Versioning

The protocol version is in the descriptor (`protocolVersion`) and in the
header of every request. The host speaks **exactly version 4**. It reads
the version before the rest of the descriptor. A service that reports an
older version does not bind, even if its descriptor does not meet the rules
of version 4 in other respects: the connection is `DEFECT PROTOCOL_TOO_OLD`
("Remote protocol 3 is too old; this RMM needs version 4") before anything
is read, and the host tries again after its waiting period (the service can
be updated at any time). A newer or missing version is `DEFECT` with a
message. The version applies per connection: two connections of type
`REMOTE` to services of different versions do not affect each other. Within
a version only backward-compatible extensions are allowed (new optional
properties); everything else increases the version.

## Testing

**The TCK for remote services** is the module `rmm_remote_tck`
(`com.wiestigo.mirador:rmm_remote_tck:1.1.0`). It tests a running service
over real https, without Mirador, whatever language the service is written
in; only the test itself is Java (JUnit). The test extends
`com.rmm.remote.tck.RemoteServiceContractTest`, starts the service and
names its address (`serviceUrl()`, as `url` of a connection) and its trust
anchor (`serviceAnchor()`, PEM, as `tls.trust`); the service installs the
certificates of `RemoteServiceIdentities.pinned()` (two connections with
two bindings). A violation is reported as a `ServiceContractViolation`.
Five cases:

1. Without a client certificate, with one that is not installed, and with
   one that is not installed but names the binding of an installed one, the
   handshake fails (a TLS alert, or the service resets the connection or
   closes it without an answer) or the service answers `401`/`403`: on
   `/descriptor`, `/health`, `/read/artifacts` (with `TO_RMM`) and a delete
   (`/write/artifact/delete`, with `FROM_RMM`). An exceeded timeout or a
   refused connection is not a rejection.
2. `403` for a missing, repeated (also with the same value) or foreign
   `X-RMM-Binding` header on the same paths; with the right header the
   service answers (the delete also with `404`).
3. Two bindings are kept apart, recognized by the text of the elements
   (identifiers may be the same in two bindings): each binding reads the
   artifact it created and not that of the other one, also not by its
   identifier (`elementIds`); an update and a delete of one binding with an
   identifier of the other change nothing there; with `COMMENTS` in `reads`
   and `writes`, a comment of one binding is read by it and not by the
   other. The case needs both directions with `ARTIFACTS` and is skipped
   otherwise.
4. The descriptor of each binding is a version 4 descriptor that the host
   binds (the rules above), and `/health` answers 2xx.
5. The outcome per element: on `/write/artifact/update`, on
   `/write/comment/create` (if `writes` contains `COMMENTS`) and on the
   declared bulk operations `ARTIFACT_CREATE`, `ARTIFACT_UPDATE` and
   `COMMENT_CREATE`, where a batch answers exactly one outcome per element
   and an update in a batch carries its receipt. The case needs `FROM_RMM`
   with `ARTIFACTS` in `writes` and is skipped otherwise.

Each case deletes what it created. Like the host, the TCK speaks only
TLS 1.3 and 1.2 with AEAD (for TLS 1.2 with ECDHE key exchange), never
follows a redirect, and sends `deadline` and `connection` (the values from
`connectionValues()`, default `{}`) in every call over POST. Its request
timeout is `requestTimeout()` (default 30 s), its project
`externalProjectId()` (default `PRJ-1`). What the host accepts, the TCK
accepts; it is stricter only where this document requires it:

- Names of the directions, phases, bulk operations, hierarchy models, types
  and scopes exactly as written here (the host also reads them in lower
  case or with whitespace), `protocolVersion` as a number
  (`"protocolVersion": 4`).
- `appliedFields` and `notes` are arrays of texts, the reasons in
  `notApplied` texts (table "Outcome per element"); the host ignores an
  `appliedFields` that is not an array.
- An `error` needs a reason (table "Outcome per element": "with a reason");
  the host reads an empty `error` as no error.
- The service must write the elements that the TCK writes
  (`artifactToWrite`, `commentToWrite`, can be overridden in the test): an
  `error` on them is a violation and not a matter of the element, and so is
  a comment without an identifier in case 3 (in case 5 a comment may also
  be skipped with a reason).

The TCK calls only the endpoints named in the five cases. It does not test
relationships, hierarchy, delta reads, attachments, variability or
metadata. Of the paging rules it tests that a read ends, that no cursor
repeats and that every page keeps the two invariants of a page; the rules
of this document apply to all the rest all the same.

**A template service** is in `rmm_remote_service_template/`: a remote
service in Java 17 with nothing but the JDK that passes all five cases
(`TestRemoteServiceTemplateContract` extends `RemoteServiceContractTest`).
It declares both directions, `FLAT`, the phases `ARTIFACTS`,
`RELATIONSHIPS` and `COMMENTS` and bulk operations for them; it offers no
attachments, variability, hierarchy, delta reads or `/metadata`. Its README
describes how to turn it into a service of your own and how to obtain the
TCK as a `test` dependency.
