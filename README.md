# Wiestigo Mirador Adapter SDK

With this SDK you build adapters that synchronize Wiestigo Mirador, the
requirements management system, with an external tool, and you test them
against the adapter contract without a running Mirador. Technical identifiers
(modules, packages, headers) carry `rmm`.

There are two ways to integrate:

- **A plugin** runs inside the Mirador process. You write it in Java against
  the contract `rmm_adapter_api` and deliver one jar.
- **A remote service** is an HTTPS service of your own, in any language, that
  Mirador addresses through the remote protocol.

## Modules

| Module | Purpose |
| --- | --- |
| `rmm_adapter_model` | The transfer model (`com.io_elements`): artifacts, relationships, comments, attachments and their fields, plus the mapping and its reader |
| `rmm_adapter_api` | The contract (`com.rmm.adapter.api`), semantically versioned |
| `rmm_adapter_http_spring` | The Spring facade: Spring clients over the contract's HTTP path |
| `rmm_adapter_tck` | The contract tests for an adapter, with reference adapters |
| `rmm_remote_tck` | The contract tests for a remote service, over HTTP |
| `rmm_adapter_template` | An adapter project to copy (CSV example, shade build) |
| `rmm_remote_service_template` | A remote service project to copy (JDK only) |

Documentation:

- [Developer guide](docs/DEVELOPER_GUIDE.md)
- [Remote protocol](docs/REMOTE_PROTOCOL.md)

## Getting the SDK

There are two ways to get the artifacts, and one more for the templates. The
current version is on the
[releases page](https://github.com/Wiestigo/wiestigo-mirador-adapter-sdk/releases).

### Released packages

Every release is published to the GitHub Packages of this repository, with
sources. Add the repository to the `pom.xml` of your project:

```xml
<repositories>
    <repository>
        <id>mirador-adapter-sdk</id>
        <url>https://maven.pkg.github.com/Wiestigo/wiestigo-mirador-adapter-sdk</url>
    </repository>
</repositories>
```

GitHub Packages asks for a sign-in even to read public Maven packages. Any
GitHub account will do: create a personal access token (classic) with the
scope `read:packages` and name it in `~/.m2/settings.xml` under the same id:

```xml
<settings>
    <servers>
        <server>
            <id>mirador-adapter-sdk</id>
            <username>YOUR_GITHUB_USER</username>
            <password>YOUR_TOKEN</password>
        </server>
    </servers>
</settings>
```

In a CI job, pass both values from secrets as environment variables and
reference them as `${env.NAME}` instead of writing the token into the file.

### Building from source

You need a JDK from version 17 and Maven 3.6.3 or newer. The contract is
compiled for Java 17; Mirador itself runs on a newer Java and loads your
adapter there.

```bash
mvn install
```

This builds all modules, runs their tests and installs the artifacts into the
local Maven repository (`~/.m2`); no account and no token are needed. A build
from source always installs version `1.1.0`, the number of the contract line
in the `pom.xml` files: a release gets its patch number only when it is
published.

### Templates

The two templates are projects to copy, not libraries. Each release carries
them as a zip (`rmm_adapter_sdk-<version>-templates.zip`) on the releases
page, with the SDK version of that release already filled in. They are also
the directories `rmm_adapter_template` and `rmm_remote_service_template` of
this repository; there the SDK version is `1.1.0`, which fits a build from
source. Each template's README says what to rename and how to build it.

## Using the artifacts

The artifacts have the group id `com.wiestigo.mirador` and the version of the
release you use:

```xml
<dependency>
    <groupId>com.wiestigo.mirador</groupId>
    <artifactId>rmm_adapter_api</artifactId>
    <version>1.1.2</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>com.wiestigo.mirador</groupId>
    <artifactId>rmm_adapter_tck</artifactId>
    <version>1.1.2</version>
    <scope>test</scope>
</dependency>
```

Mirador supplies the contract at run time, so it does not belong in your jar
(`provided`). The template `rmm_adapter_template` shows the complete setup.

## Versioning

The SDK version follows the contract: `1.1.x` is contract major 1, minor 1;
the patch number counts the releases of that contract.
Inside a major version, changes stay backward compatible; the developer guide
states the rules in its section on versioning.

## License

The SDK is licensed under the [Apache License, Version 2.0](LICENSE),
Copyright 2026 Wiestigo UG (haftungsbeschränkt); see also [NOTICE](NOTICE).

The two templates, `rmm_adapter_template` and `rmm_remote_service_template`,
are licensed under MIT No Attribution (MIT-0), see their `LICENSE` files
([adapter](rmm_adapter_template/LICENSE),
[remote service](rmm_remote_service_template/LICENSE)): copy them and build your adapter or service on them without keeping a
copyright notice, and license the result as you like.

The license grants no rights to the names "Wiestigo" and "Mirador".
