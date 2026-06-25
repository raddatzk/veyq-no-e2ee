# ADR 0013: Automated Dependency Updates

## Status

Accepted

## Context

veyq depends on multiple public dependency ecosystems: the frontend uses npm, the backend uses Gradle, runtime images are defined through Dockerfiles and CI uses GitHub Actions. These dependencies need regular updates without turning the repository into a stream of small unrelated dependency pull requests.

The project is self-hostable and should keep security maintenance visible. At the same time, broad dependency changes should remain easy to review and should not block urgent security fixes.

## Decision

Use GitHub Dependabot for automated dependency update pull requests.

Dependabot checks daily. Regular version updates are grouped into two review lanes:

- One `app-runtime` multi-ecosystem pull request for frontend npm dependencies, backend Gradle dependencies and Dockerfile base images.
- One GitHub Actions pull request for workflow action updates.

Each regular update lane is limited to one open pull request at a time. If a regular Dependabot pull request is still open, Dependabot should wait instead of opening another regular pull request for that lane. Dependabot security update pull requests remain separate and may still be opened independently.

The root and backend npm lockfiles are not configured as npm update targets because they do not have matching `package.json` manifests. Future app packages should get their own Dependabot lanes once they exist as separate dependency manifests with an owner-worthy review boundary.

Direct dependency declarations should use stable releases only. Release candidates, beta, alpha, development and snapshot versions must not be committed as direct dependency versions. Transitive prerelease dependencies may be accepted when they are pulled by a stable direct release and the project checks pass.

npm `overrides` may be used as temporary security or compatibility patches for transitive dependencies when waiting for an upstream dependency release would leave known issues unresolved. Every frontend override must be checked by `npm run check:overrides`, and the regular frontend `npm run check` includes that check. An override that is no longer present in `package-lock.json`, or no longer resolves to the version declared in `package.json`, should be removed or updated in the same dependency maintenance change.

## Consequences

Dependencies stay current with predictable daily checks and low pull request volume.

Frontend, backend and Docker runtime changes can be tested together because they describe the runnable application stack. GitHub Actions updates stay separate because they affect CI behavior rather than the application runtime.

Large grouped updates can make a failing dependency harder to isolate. When that happens, the grouped Dependabot pull request may need to be closed or temporarily narrowed before retrying.

Temporary npm overrides keep security fixes visible in source control instead of relying on local lockfile edits. They also add a small maintenance responsibility: dependency update pull requests need to keep the override check green and remove stale overrides when upstream dependencies no longer require them.
