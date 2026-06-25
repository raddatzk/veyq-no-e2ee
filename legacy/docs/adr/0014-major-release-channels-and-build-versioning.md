# ADR 0014: Major Release Channels and Build Versioning

## Status

Accepted

## Context

veyq is self-hostable and supports automatic compatible updates through container image channels. Operators may choose a major image tag such as `ghcr.io/raddatzk/veyq:1` so their instance receives compatible updates without opting into breaking operational or API changes.

The current public server version format is `major.build`. The major version comes from the repository `VERSION` file and breaking changes increment the major. Previously the build component was derived from the GitHub Actions run number, which is globally monotonic across the repository. That makes versions unique, but it means a new major can start at a high inherited build number such as `2.738` instead of a major-local sequence such as `2.1`.

Release branches are also needed once a newer major exists. A previous major may still need compatible fixes while `main` continues with the next breaking line.

## Decision

Keep the public server version format as `major.build`, for example `1.42`.

Keep `VERSION` as the source for the current major only. The file contains values such as `1`, not a full semantic version.

Stop deriving the build number from `GITHUB_RUN_NUMBER` for release versions. During container publish, compute the next build number from already published GHCR image tags for the current major:

- List existing image tags matching `^<major>\.[0-9]+$`.
- Choose the highest build number for that major.
- Publish the next version as `<major>.<highest+1>`.
- If no matching tag exists, publish `<major>.1`.

Use release branches for maintained major lines:

- Before `main` changes from major `N` to `N+1`, create `release/N.x` from the last compatible `main` commit.
- `release/N.x` keeps `VERSION=N`.
- `main` updates `VERSION=N+1`.
- Compatible fixes can continue on `release/N.x` and publish new `N.<build>` images after `main` has moved on.
- `release/N.x` receives maintenance for one year after the first `N+1.<build>` image is published.

Release branch maintenance is limited to security fixes and critical bugs. Critical bugs are issues that can cause data loss, broken installs, broken upgrades or unusable self-hosted operation. Non-critical bug fixes and new features may target only the active major unless maintainers explicitly decide to backport them.

Publish image tags by branch type:

- `main` and `master` publish `<major>.<build>`, `<major>`, `sha-*` and `latest`.
- `release/<major>.x` publishes `<major>.<build>`, `<major>` and `sha-*`, but never `latest`.
- Git tags matching `v*` may publish the git ref tag as today, but must not unexpectedly move another major channel.

Configure workflow concurrency per major channel, for example `veyq-release-major-1`, so two publishes for the same major cannot compute and push the same next build number at the same time.

The major tag, such as `ghcr.io/raddatzk/veyq:1`, is the compatible auto-update channel for that major. Exact tags, such as `1.42`, are immutable release history.

Breaking operational or API changes require a new major. Additive fixes and compatible improvements stay within the existing major channel.

## Consequences

New major lines start with a readable major-local build sequence such as `2.1` instead of inheriting a repository-wide GitHub Actions run number.

GHCR becomes the source of truth for already published release build numbers. If a publish fails before pushing tags, the next run recomputes the same next build number. If a tag was pushed, later runs advance from that tag.

Release branches let operators stay on a maintained previous major while the default branch moves to the next breaking line. Automatic updates remain compatible when operators follow a major tag.

After the one-year maintenance window ends, the previous major channel may stop receiving image updates. Operators should upgrade to a maintained major line before that window expires.

The container workflow queries GHCR tags, filters unrelated tags such as `latest`, `<major>`, `sha-*`, `build-*` and `v*`, computes the next major-local build, and applies branch-specific tag publication rules. The development documentation uses the registry-derived release versioning rule instead of the old `major.GITHUB_RUN_NUMBER` wording.
