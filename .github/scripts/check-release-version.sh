#!/usr/bin/env bash
# Checks the version a release was started with and writes it, without a leading v, to GITHUB_OUTPUT.
# usage: check-release-version.sh <version>
# Reads GITHUB_REF, GITHUB_REPOSITORY and GH_TOKEN (for gh) from the environment of the workflow.
set -euo pipefail

input="${1:?usage: check-release-version.sh <version>}"
version="${input#v}"

fail() {
  echo "::error::$1"
  exit 1
}

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  fail "'$input' is not a version (MAJOR.MINOR.PATCH, for example 1.1.0)"

[ "${GITHUB_REF:-}" = refs/heads/main ] ||
  fail "A release is made from main only (started on ${GITHUB_REF:-no ref})"

# The SDK version follows the contract: MAJOR.MINOR is the contract version, PATCH counts its releases
api=rmm_adapter_api/src/main/java/com/rmm/adapter/api/AdapterApiVersion.java
major=$(sed -n 's/.*int MAJOR = \([0-9][0-9]*\);.*/\1/p' "$api")
minor=$(sed -n 's/.*int MINOR = \([0-9][0-9]*\);.*/\1/p' "$api")
[ -n "$major" ] && [ -n "$minor" ] || fail "Cannot read the contract version from $api"
[[ "$version" == "$major.$minor."* ]] ||
  fail "The contract is $major.$minor: a release of it is $major.$minor.PATCH, not $version"

if gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/v$version" --silent 2>/dev/null; then
  fail "The tag v$version exists already. Choose the next version."
fi
if gh release view "v$version" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  fail "The release v$version exists already. Choose the next version."
fi

echo "Release $version from ${GITHUB_SHA:-the current commit}"
echo "version=$version" >> "${GITHUB_OUTPUT:-/dev/stdout}"
