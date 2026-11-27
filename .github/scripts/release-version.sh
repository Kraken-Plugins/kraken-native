#!/usr/bin/env bash
# Picks the version the Build workflow packages, from version.txt and the release tags (v1.2.3), and prints
# version=<version>, tag=v<version> and release=<true|false> lines for $GITHUB_OUTPUT.
#
# version.txt holds major.minor.patch. The first release of that line is the version as written; each release after it
# is the next patch above the highest vX.Y.* tag, so 2.1.0 releases as 2.1.0, 2.1.1, 2.1.2, ... until version.txt says
# 2.2.0 or 3.0.0. A commit that already has a release tag keeps its version, so running the workflow again for it
# republishes that release instead of making a new one.
#
# RELEASE=true marks a build that is published, as long as HEAD is still the tip of origin/$BRANCH (default master) or
# already released. Any other build packages the version it would release with -SNAPSHOT appended. Run it from a
# checkout with its branches and tags fetched.
set -euo pipefail

base="$(tr -d '[:space:]' < version.txt)"
number='(0|[1-9][0-9]*)'
if [[ ! "$base" =~ ^$number\.$number\.$number$ ]]; then
  echo "::error file=version.txt::version.txt must hold a version such as 2.1.0, not '$base'" >&2
  exit 1
fi
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"

released="$(git tag --points-at HEAD | grep -E "^v$number\.$number\.$number$" | sort -V | tail -n 1 || true)"
if [[ -n "$released" ]]; then
  version="${released#v}"
  echo "HEAD is already released as $released" >&2
else
  last="$(git tag -l "v$major.$minor.*" | sed -nE "s/^v$major\.$minor\.$number$/\1/p" | sort -n | tail -n 1)"
  if [[ -z "$last" || "$patch" -gt "$last" ]]; then
    version="$base"
  else
    version="$major.$minor.$((last + 1))"
  fi
  echo "version.txt says $base; the highest v$major.$minor release is ${last:+v$major.$minor.}${last:-none}" >&2
fi

# Only the branch's newest commit is released, so re-running an old run never publishes old files under a newer version
branch="${BRANCH:-master}"
if [[ "${RELEASE:-}" == true && -z "$released" ]]; then
  tip="$(git rev-parse -q --verify "refs/remotes/origin/$branch^{commit}" || true)"
  if [[ "$tip" != "$(git rev-parse HEAD)" ]]; then
    echo "::notice::Not releasing: HEAD isn't the tip of origin/$branch${tip:+ (${tip:0:7})}, whose run releases it" >&2
    RELEASE=false
  fi
fi

if [[ "${RELEASE:-}" == true ]]; then
  release=true
else
  release=false
  version="$version-SNAPSHOT"
fi

echo "Version $version (release: $release)" >&2
echo "version=$version"
echo "tag=v${version}"
echo "release=$release"
