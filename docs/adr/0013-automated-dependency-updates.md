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

## Consequences

Dependencies stay current with predictable daily checks and low pull request volume.

Frontend, backend and Docker runtime changes can be tested together because they describe the runnable application stack. GitHub Actions updates stay separate because they affect CI behavior rather than the application runtime.

Large grouped updates can make a failing dependency harder to isolate. When that happens, the grouped Dependabot pull request may need to be closed or temporarily narrowed before retrying.
