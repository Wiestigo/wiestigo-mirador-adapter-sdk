# Contributing

Contributions are welcome: reports of defects, questions, improvements to the
documentation, and pull requests.

## Issues

Open an issue for a defect, a question or a proposal. For a defect, say which
module and version you use, what you expected and what happened; a failing
test or a minimal adapter that shows it helps most.

Do not report a security problem in an issue. See [SECURITY.md](SECURITY.md).

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Build and test with any JDK from version 17:

   ```bash
   mvn verify
   ```

   The build of a pull request runs the same on JDK 17 and JDK 25. Both must
   be green.
3. Describe in the pull request what changes and why, and how you tested it.

Small, focused pull requests are reviewed fastest. For a larger change, open
an issue first, so that the direction is agreed before you write the code.

### What to keep in mind

- **The contract is frozen within a major version.** Public and protected
  members of `com.rmm.adapter.api` and of the transfer model `com.io_elements`
  are not removed, renamed or changed in their signature; additions are
  default methods or new types and belong to a new minor version. The
  developer guide states the rules in "12. Versioning". For
  `com.rmm.adapter.api` the test `TestAdapterApiSignatures` pins the surface
  in `rmm_adapter_api/src/test/resources/frozen-api/major-1.txt`. A change to
  that list is a decision of the maintainers: propose it in an issue first.
- **The contract carries no third-party types.** `rmm_adapter_api` uses only
  the JDK, itself and the transfer model, so that a plugin sees nothing else
  through the host's class loader.
- **The TCKs test the contract and the protocol, nothing else.** A new check
  needs a rule in the Javadoc of the contract or in
  `docs/REMOTE_PROTOCOL.md` that it enforces, and a test that shows a
  violating adapter or service failing it.
- **Java 17** is the source level of every module.
- **Tests come with the change.** A fix has a test that fails without it.
- **Messages are for adapter authors.** A violation says what the adapter did
  and what the contract expects, without internal names.

## License of contributions

The SDK is licensed under the Apache License, Version 2.0, the two templates
under MIT No Attribution (see [README](README.md#license)). You submit a
contribution under the license of the part you change, as section 5 of the
Apache License says for the SDK; the same applies to the templates and their
license.

## Releases

Releases are made by the maintainers. A pull request does not change version
numbers: the `pom.xml` files carry the number of the contract line, and a
release sets its own version when it is published.
