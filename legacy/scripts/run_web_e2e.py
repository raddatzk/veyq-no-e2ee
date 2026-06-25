#!/usr/bin/env python3
"""Run the web `VEYQ_E2E=1` suites against a live backend, one fresh backend per test file.

Every e2e file registers its own Server Owner, and the server is single-owner-per-boot
(`jdbc:h2:mem`, which only resets when the JVM restarts). So the files cannot share a
backend: this orchestrator boots a fresh backend, runs exactly one e2e file against it,
tears it down, and moves on. It aggregates the per-file results and exits non-zero if any
file failed — the shape CI consumes.

Usage:
    scripts/run_web_e2e.py [FILE ...]

With no arguments it discovers every `frontend/src/**/*.e2e.test.ts`. Paths may be given
relative to the repo root or to `frontend/`.

Environment:
    VEYQ_JAR        Backend runnable fast-jar. Default: backend/build/quarkus-app/quarkus-run.jar
    VEYQ_PORT       Backend HTTP port. Default: 8080
    VEYQ_BOOT_SECS  Readiness timeout per boot. Default: 90
    VEYQ_TEST_MS    Per-test timeout passed to vitest. Default: 60000
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FRONTEND = REPO / "frontend"
PORT = int(os.environ.get("VEYQ_PORT", "8080"))
API_BASE = f"http://localhost:{PORT}"
JAR = Path(os.environ.get("VEYQ_JAR", REPO / "backend/build/quarkus-app/quarkus-run.jar"))
BOOT_SECS = int(os.environ.get("VEYQ_BOOT_SECS", "90"))
TEST_MS = os.environ.get("VEYQ_TEST_MS", "60000")

# e2e-ready backend: rate limiting off (the suites hammer auth from one host) and mail mocked.
BACKEND_ENV = {
    **os.environ,
    "VEYQ_RATELIMIT_ENABLED": "false",
    "VEYQ_MAIL_MOCK": "true",
    "QUARKUS_HTTP_PORT": str(PORT),
}


def discover() -> list[Path]:
    return sorted(FRONTEND.glob("src/**/*.e2e.test.ts"))


def normalize(arg: str) -> Path:
    """Accept a path relative to the repo root or to frontend/ (or absolute)."""
    p = Path(arg)
    for candidate in (p, REPO / p, FRONTEND / p):
        if candidate.is_file():
            return candidate.resolve()
    raise SystemExit(f"e2e file not found: {arg}")


def wait_ready(deadline: float) -> bool:
    url = f"{API_BASE}/q/health/ready"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                if r.status == 200:
                    return True
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(0.5)
    return False


def boot() -> subprocess.Popen:
    log = open(REPO / "build-e2e-backend.log", "w")
    return subprocess.Popen(
        ["java", "-jar", str(JAR)],
        cwd=str(REPO),
        env=BACKEND_ENV,
        stdout=log,
        stderr=subprocess.STDOUT,
    )


def kill(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=10)


def run_file(test_path: Path) -> bool:
    rel = test_path.relative_to(FRONTEND).as_posix()
    print(f"\n=== e2e: {rel} ===", flush=True)
    proc = boot()
    try:
        if not wait_ready(time.time() + BOOT_SECS):
            print(f"FAIL {rel}: backend did not become ready in {BOOT_SECS}s", flush=True)
            return False
        result = subprocess.run(
            ["npx", "vitest", "run", rel, "--testTimeout", TEST_MS],
            cwd=str(FRONTEND),
            env={**os.environ, "VEYQ_E2E": "1", "VEYQ_API_BASE": API_BASE},
        )
        return result.returncode == 0
    finally:
        kill(proc)


def main(argv: list[str]) -> int:
    if not JAR.is_file():
        raise SystemExit(f"backend jar missing: {JAR}\nBuild it first: (cd backend && ./gradlew quarkusBuild)")
    files = [normalize(a) for a in argv] if argv else discover()
    if not files:
        raise SystemExit("no e2e files found")
    print(f"Running {len(files)} web e2e file(s), one fresh backend each.", flush=True)

    failed: list[str] = []
    for f in files:
        if not run_file(f):
            failed.append(f.relative_to(FRONTEND).as_posix())

    print("\n==================== web e2e summary ====================", flush=True)
    print(f"total: {len(files)}  passed: {len(files) - len(failed)}  failed: {len(failed)}", flush=True)
    for name in failed:
        print(f"  FAILED  {name}", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
