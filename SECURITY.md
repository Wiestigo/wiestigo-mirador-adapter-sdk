# Security policy

## Reporting a vulnerability

Please report a suspected vulnerability by e-mail to
[support@wiestigo.com](mailto:support@wiestigo.com), not in a public issue or
pull request.

It helps if the report says:

- which module and which version are affected (for example
  `rmm_adapter_tck` 1.1.2),
- what an attacker can do and under which conditions,
- how to reproduce it, ideally with a minimal example.

We confirm that we received the report and tell you how we proceed. Please
give us the time to provide a fix before you publish details.

## Scope

This policy covers what this repository contains: the transfer model, the
adapter contract, the Spring facade, the two TCKs and the two templates. A
vulnerability in Wiestigo Mirador itself can be reported to the same address.

The templates are starting points. An adapter or a remote service built on
them is the responsibility of whoever builds and operates it.

## Supported versions

Fixes go into the next release of the current contract line (`1.1.x`). Older
patch releases are not updated; take the latest release.
