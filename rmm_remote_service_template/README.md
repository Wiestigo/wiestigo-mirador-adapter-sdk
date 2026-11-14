# Remote service template

A remote adapter service for Wiestigo Mirador in Java 17, using only the JDK.
Mirador reaches the service through a connection of type `REMOTE` and speaks
protocol version 4 with it (`docs/REMOTE_PROTOCOL.md` in the SDK). Copy this
module and replace the in-memory tool with calls to your own tool.

## What it does

| Class | Role |
| --- | --- |
| `RemoteService` | the server: TLS, the three checks of every request, the endpoints, the descriptor |
| `PinStore` | the pinned client certificates from a directory, read again after every change |
| `InMemoryTool` | the tool of **one** binding in memory: artifacts, links, comments |
| `ServiceSettings` | address, key store and pin directory, from the environment |
| `Json` | JSON without a library; use any JSON library in your own service |

Every request passes three checks before an endpoint sees it:

1. **TLS** 1.3 or 1.2 with AEAD cipher suites. The handshake requires a client
   certificate and accepts only a pinned one: byte for byte equal and valid.
   The name in a certificate grants nothing, because anyone can issue a
   certificate with `urn:rmm:binding:<id>`.
2. **Check per request.** The certificate is checked against the pin store
   again, otherwise `401`. A removed certificate stops working with the next
   request, even on an open TLS connection.
3. **Binding.** The header `X-RMM-Binding` appears exactly once and names the
   binding of the certificate (`URI:urn:rmm:binding:<bindingId>`), otherwise
   `403`.

The request then works on the tool of **its** binding: two connections (for
example of two tenants) share neither data nor state.

The template keeps data in memory only, ignores the run's `deadline`, declares
no `connectionKeys` and does not offer attachments, variability, hierarchy,
delta reads or `/metadata`. It does not limit connections per peer; do that in
front of the service (firewall, proxy). It sets no timeout for request body
and response; the JDK system properties `sun.net.httpserver.maxReqTime` and
`sun.net.httpserver.maxRspTime` (seconds) limit them for the whole process.

## Turn it into your own service

1. Copy the module; rename the package, `systemKey` and `displayName`.
2. Replace `InMemoryTool` with calls to your tool, using the access **this
   binding** has there. Keep the shapes of the answers.
3. Adapt `RemoteService.descriptor` to what your service supports. A service
   can only narrow the upper limit of the type `REMOTE`.
4. Keep the three checks. While the service runs on the JDK `HttpsServer`,
   also keep the closed connection after a refusal (`RemoteService.refuse`)
   and the admission time limit (`RemoteService.admissionTimeLimit`).
5. Never report a problem of the tool behind the service as `401` or `403`
   (to Mirador that means: wrong certificate). Use `503` or `429` with
   `Retry-After`, `502`, or `500` with a reason.

## Build and test

The TCK is `com.wiestigo.mirador:rmm_remote_tck:1.1.0`, scope `test`. In a
checkout of the SDK, run `mvn install` once with any JDK from 17; it puts the
TCK and its parent `com.wiestigo.mirador:rmm_adapter_sdk` into your local
Maven repository. In your copy, replace the parent of the module with your own
versions (compiler release 17, Surefire 3.x, JUnit 6 or 5.x), set the version
of the TCK dependency (the template uses `${project.version}`) and run
`mvn test`. The main code needs only the JDK.

Keep `TestRemoteServiceTemplateContract`: it extends
`RemoteServiceContractTest`, and all five cases must pass. Let its
`startService()` start your service.

## Operate the service

Mirador authenticates **only with mutual TLS**; there is no token.

### 1. Key store of the service

A PKCS#12 key store with exactly one key. The certificate is self-signed
(Mirador pins it and does not check the host name) or issued by a certificate
authority (Mirador checks chain and host name):

```bash
keytool -genkeypair -alias service -dname "CN=remote.example.org" \
  -ext san=dns:remote.example.org -validity 365 \
  -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -storetype PKCS12 -keystore service.p12
keytool -exportcert -rfc -alias service -keystore service.p12 -file service.pem
```

Give `service.pem` to the Mirador administrator.

### 2. Start the service

```bash
export REMOTE_SERVICE_KEYSTORE=/etc/remote-service/service.p12
export REMOTE_SERVICE_KEYSTORE_PASSWORD=...
export REMOTE_SERVICE_PINS=/etc/remote-service/pins   # directory of the client certificates
export REMOTE_SERVICE_PORT=8443                       # default 8443; address: REMOTE_SERVICE_ADDRESS
java -cp target/classes com.example.remoteservice.RemoteService
```

Without pinned certificates the service accepts no client.

### 3. Create the connection in Mirador

The Mirador administrator creates a connection of type `REMOTE` with `url` =
`https://remote.example.org:8443` and `tls.trust` = `service.pem`. Mirador
generates the client identity of the connection itself: an ECDSA P-256 key and
a self-signed certificate with the `bindingId` in the subject alternative
name, valid for one year. The private key never leaves Mirador.

### 4. Pin the certificate of the connection

The administrator downloads the certificate of the connection (the
administration shows its SHA-256 fingerprint) and gives it to you. Put it into
the pin directory as a file of its own (`*.pem`, `*.crt` or `*.cer`; any
unique name) and compare the fingerprint:

```bash
keytool -printcert -file /etc/remote-service/pins/<name>.pem | grep SHA256
```

- **Confirm the fingerprint through a second channel.** The binding in the
  certificate is self-declared: whoever sends you a self-made certificate
  with the binding of an existing connection gets the data of that connection
  once you pin it. Confirm the fingerprint with the administrator of the
  tenant through another channel than the one the certificate came through
  (for example by phone). This applies to every new certificate.
- **Never overwrite a pin file in place.** A new certificate is a new file; an
  old one is deleted. If an overwritten file has the same size and roughly the
  same modification time, the service does not notice the change and keeps
  trusting the old content.

The service reads the directory again at the next request, without a restart.
The connection becomes ready when Mirador binds it again afterwards: before
the next run, at the next save, or when the administrator checks the
connection. Each connection gets its own certificate and its own file.

### 5. Rotate the certificate of a connection

The certificate is valid for one year; Mirador warns 30 days before it
expires. Rotation works without downtime:

1. **Accept both.** Mirador prepares the new certificate while the old one
   stays active. Confirm its fingerprint through a second channel and add it
   as a **new file** next to the old one. It names the same binding; from the
   next request on both are accepted and see the same data.
2. **Mirador probes first.** Before switching, Mirador binds once with the new
   certificate. If the service does not accept it, nothing changes: the
   administration reports `PROBE_FAILED` and the old certificate stays active.
   If the switch is scheduled for a set time, pin the new certificate before
   that time; otherwise the probe fails and Mirador retries every hour.
3. **Remove the old one once it is retired.** When the probe succeeds, the
   administration shows the old certificate as `RETIRED`. Delete its file only
   then: before that, a running synchronization may still use it.

### 6. Expiry and removal

The service does not accept an expired certificate. When a connection is
deleted in Mirador or its identity is revoked, delete its file. A copy or a
move of a tenant creates new connections with new certificates; pin them as in
step 4.

### 7. Renew the certificate of the service

Mirador shows a notice 30 days before the service certificate expires.
`tls.trust` may hold several certificates: the administrator adds the new
service certificate, then you replace the key store and restart the service,
and finally the administrator removes the old certificate from `tls.trust`.
