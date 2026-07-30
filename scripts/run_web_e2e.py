#!/usr/bin/env python3
"""Run web's `VEYQ_E2E=1` suites against a live backend + a real object store, one file at a time.

**What it does.** Brings up the bundled stack from `infra/docker-compose.yml` (`--profile s3`: MariaDB, Garage,
the init sidecar, Traefik), then per e2e file: gives it its OWN database, boots a fresh fast-jar under
`-Dquarkus.profile=e2e` against that database, waits for `/q/health/ready`, runs exactly that one file, and
tears the boot down. It aggregates: every failing file is reported and the exit code is non-zero if ANY failed,
never short-circuiting on the first.

**Why a fresh instance per file.** Every suite cold-births its own Server Owner and asserts
`setupRequired === true`, and a server is single-owner-per-instance. `ci.yml` used to call this "a fresh in-mem
DB per file", which does not work: `quarkus.datasource.db-kind` is a BUILD-TIME property baked at augmentation
under `%prod`, so a runtime `QUARKUS_DATASOURCE_DB_KIND=h2` is ignored and the *MariaDB* driver rejects the H2
URL. One MariaDB with a fresh `veyq_e2e_<n>` database per file is a fresh instance in the only sense the suites
care about — at one container, and with `ci.yml`'s "build the jar once" intact.

**Why the store cannot be a GH Actions service container.** Garage bind-mounts `/etc/garage.toml` from the repo
(service containers start before checkout), its official image is shell-less `scratch`, its bootstrap is a
mandatory multi-step sequence, and the init sidecar is built locally from the repo. So the stack comes up through
compose, which every self-hoster already has — nothing here depends on anything the bundled stack does not ship.

**Not a `validate_*` guard.** It needs Docker and a built jar, so it cannot join the buildless trust-chain glob;
CI calls it directly. The one part of its contract that IS decidable without either — that it cannot report green
having run nothing — is guarded by `scripts/validate_e2e_discovery.py`, which imports `discover()` from here.

Usage:
    python3 scripts/run_web_e2e.py [FILE ...]        # from the repo root

With no arguments it runs every `web/src/**/*.e2e.test.ts` — that is how CI invokes it. Paths may be given
relative to the repo root or to `web/`, which is a local debugging convenience only.

Prerequisite: the fast-jar exists (`cd backend && ./gradlew quarkusBuild`). CI builds it once, before this runs.

Environment (all optional):
    VEYQ_JAR              Backend fast-jar. Default: backend/build/quarkus-app/quarkus-run.jar
    VEYQ_PORT             Backend HTTP port. Default: 8080
    VEYQ_HOST             Tenant domain + fetch Host. Default: veyq.localhost
    VEYQ_BOOT_SECS        Readiness timeout per boot. Default: 120
    VEYQ_TEST_MS          Default per-test timeout passed to vitest. Default: 120000
    VEYQ_DB_ROOT_PASSWORD MariaDB root password. Default: the documented compose throwaway.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WEB = REPO / "web"
COMPOSE_FILE = REPO / "infra" / "docker-compose.yml"
COMPOSE = ["docker", "compose", "-f", str(COMPOSE_FILE), "--profile", "s3"]

PORT = int(os.environ.get("VEYQ_PORT", "8080"))
# The tenant domain AND the fetch Host: `veyq.localhost` is a valid dotted domain (so POST /api/tenants and the
# invite mailer's hostname check accept it) and an RFC 6761 loopback name. It must be real in the URL, not a
# spoofed header — tenant resolution is server-side from `Host`, and fetch/undici strips a manually set one.
HOST = os.environ.get("VEYQ_HOST", "veyq.localhost")
BASE = f"http://{HOST}:{PORT}"
JAR = Path(os.environ.get("VEYQ_JAR", REPO / "backend/build/quarkus-app/quarkus-run.jar"))
BOOT_SECS = int(os.environ.get("VEYQ_BOOT_SECS", "120"))
TEST_MS = os.environ.get("VEYQ_TEST_MS", "120000")

# Documented throwaway credentials from infra/docker-compose.yml — not secrets. The app user `veyq` holds grants
# on the `veyq` database ONLY, so each e2e database is created as root and then granted to it.
DB_ROOT_PASSWORD = os.environ.get("VEYQ_DB_ROOT_PASSWORD", "change-me-root")
DB_APP_USER = "veyq"

GARAGE_INIT_CONTAINER = "veyq-garage-init"

# Readiness is probed on `GET /api/setup`, NOT on `/q/health/ready`. This backend has no `smallrye-health`
# extension — grep the repo for `q/health` and you get nothing — so the health endpoint the recovered skeleton
# polled has never existed here, and polling it just times out behind a backend that started fine 118s earlier.
# `/api/setup` is the better probe anyway: unauthenticated, reachable whether or not setup is complete, served
# only once Flyway has migrated, and the first call every suite makes.
READY_URL = f"http://127.0.0.1:{PORT}/api/setup"


class NoE2eFilesError(RuntimeError):
    """Discovery matched no e2e file. A hard error, never an empty-but-successful run.

    This is the false negative the whole job exists to remove: an orchestrator that reports green because it ran
    nothing looks exactly like an orchestrator whose suites all passed. `validate_e2e_discovery.py` pins it.
    """


def log(msg: str) -> None:
    """A silent orchestrator is unreviewable in CI — every step announces itself."""
    print(f"[e2e] {msg}", flush=True)


def discover(root: Path = WEB) -> list[Path]:
    """Every web e2e suite, sorted. Raises [NoE2eFilesError] rather than returning an empty list."""
    files = sorted(root.glob("src/**/*.e2e.test.ts"))
    if not files:
        raise NoE2eFilesError(f"no *.e2e.test.ts found under {root}/src — refusing to report success on zero files")
    return files


def normalize(arg: str) -> Path:
    """Accept a path relative to the repo root or to web/ (or absolute)."""
    p = Path(arg)
    for candidate in (p, REPO / p, WEB / p):
        if candidate.is_file():
            return candidate.resolve()

    raise SystemExit(f"[e2e] e2e file not found: {arg}")


def run(cmd: list[str], **kw: object) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, **kw)  # type: ignore[arg-type]


def compose_up() -> None:
    """Bring up the bundled stack, then wait on BOTH halves it provides.

    The wait is two-step on purpose: a single `up --wait` on the whole project exits 1, because `garage-init` is a
    one-shot sidecar that exits even on success and `--wait` scores any exit as failure. So step one starts
    everything, step two waits on mariadb's healthcheck (Flyway needs it accepting connections), and step three
    waits for the init sidecar to have exited 0 — which is what makes the BUCKET ready, not just the store
    process. Skipping step three is how `media.e2e` fails in the PUT on a cold start.
    """
    log(f"compose up (--profile s3) from {COMPOSE_FILE.relative_to(REPO)}")
    run(COMPOSE + ["up", "-d"])
    log("waiting for mariadb to report healthy")
    run(COMPOSE + ["up", "-d", "--wait", "mariadb"])
    wait_garage_init()


def wait_garage_init(timeout: int = 120) -> None:
    """Wait for the one-shot init sidecar to have exited, and insist it exited 0."""
    log(f"waiting for {GARAGE_INIT_CONTAINER} to finish bucket init")
    deadline = time.time() + timeout
    while time.time() < deadline:
        probe = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Status}}|{{.State.ExitCode}}", GARAGE_INIT_CONTAINER],
            capture_output=True, text=True,
        )
        if probe.returncode == 0:
            status, _, code = probe.stdout.strip().partition("|")
            if status == "exited":
                if code != "0":
                    raise SystemExit(
                        f"[e2e] STORE INIT FAILED: {GARAGE_INIT_CONTAINER} exited {code}. "
                        f"Inspect it with: docker logs {GARAGE_INIT_CONTAINER}"
                    )
                log("store bucket is initialized")
                return
        time.sleep(1)
    raise SystemExit(f"[e2e] STORE INIT TIMEOUT: {GARAGE_INIT_CONTAINER} did not exit within {timeout}s")


def mysql(sql: str) -> None:
    """Run SQL as root inside the compose mariadb container."""
    run(COMPOSE + ["exec", "-T", "mariadb", "mariadb", "-uroot", f"-p{DB_ROOT_PASSWORD}", "-e", sql])


def fresh_database(name: str) -> None:
    """Drop and recreate this file's database, then grant it to the app user.

    DROP-then-CREATE rather than a run-unique name, so a second run in a row is just as fresh as the first
    without leaking a database per run. The GRANT is not optional: the compose `veyq` user is provisioned for the
    `veyq` database alone, and without it the boot fails with an access-denied rather than a missing-database
    error — the more confusing of the two.
    """
    log(f"provisioning a fresh database {name}")
    mysql(
        f"DROP DATABASE IF EXISTS {name}; CREATE DATABASE {name}; "
        f"GRANT ALL PRIVILEGES ON {name}.* TO '{DB_APP_USER}'@'%';"
    )


def port_is_taken() -> bool:
    """Something already answering on our port would be readiness-polled as if it were ours."""
    try:
        with urllib.request.urlopen(READY_URL, timeout=2):
            return True
    except urllib.error.HTTPError:
        return True  # answering, just not 200 — still not ours to use
    except (urllib.error.URLError, OSError):
        return False


def wait_ready(deadline: float, proc: subprocess.Popen) -> str | None:
    """Poll until the backend serves the readiness probe. Returns None when ready, else why it never was.

    A wrong status is reported rather than retried into the timeout. That distinction is not hypothetical: polling
    a path this backend does not serve looks exactly like a slow boot, and a 404 retried for 120s reads as "the
    backend never came up" while the log says it started in 2.3s.
    """
    last_status: int | None = None
    while time.time() < deadline:
        if proc.poll() is not None:
            return f"the JVM exited with code {proc.returncode} before becoming ready"
        try:
            with urllib.request.urlopen(READY_URL, timeout=2) as r:
                if r.status == 200:
                    return None
                last_status = r.status
        except urllib.error.HTTPError as e:
            last_status = e.code
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(0.5)
    if last_status is not None:
        return f"the readiness probe {READY_URL} answered {last_status}, expected 200"
    return f"nothing answered the readiness probe {READY_URL} within {BOOT_SECS}s"


def boot(database: str, secret_file: Path, log_path: Path) -> subprocess.Popen:
    """Boot the fast-jar under `%e2e` against this file's own database.

    Two per-boot overrides carry state that must NOT be shared between boots:
      - the datasource URL, so each file gets its own schema;
      - `veyq.security.config-secret-file`, whose default resolves against the process CWD. N boots from the repo
        root would share one KEK file and leave an untracked `0600` secret beside the repo (ADR 0050 amendment).
    """
    env = {
        **os.environ,
        "QUARKUS_DATASOURCE_JDBC_URL": f"jdbc:mariadb://127.0.0.1:3306/{database}",
        "QUARKUS_HTTP_PORT": str(PORT),
        "VEYQ_SECURITY_CONFIG_SECRET_FILE": str(secret_file),
    }
    handle = log_path.open("w")
    return subprocess.Popen(
        ["java", "-Dquarkus.profile=e2e", "-jar", str(JAR)],
        cwd=str(REPO), env=env, stdout=handle, stderr=subprocess.STDOUT,
    )


def kill(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=10)


def tail(path: Path, lines: int = 40) -> None:
    """Print the end of a boot log. In CI this is the only window into a failed boot."""
    if not path.is_file():
        return
    body = path.read_text(errors="replace").splitlines()
    print(f"[e2e] --- last {min(lines, len(body))} lines of {path.name} ---", flush=True)
    for line in body[-lines:]:
        print(f"[e2e]   {line}", flush=True)


def run_file(index: int, test_path: Path, workdir: Path, stamp: str) -> str | None:
    """Run one e2e file against its own fresh backend. Returns None on success, else a failure reason."""
    rel = test_path.relative_to(WEB).as_posix()
    database = f"veyq_e2e_{index}"
    log(f"=== {rel} (database {database}) ===")
    fresh_database(database)

    secret_file = workdir / f"config-secret-{index}.key"
    boot_log = workdir / f"backend-{index}.log"
    proc = boot(database, secret_file, boot_log)
    try:
        log(f"waiting up to {BOOT_SECS}s for the backend to become ready")
        not_ready = wait_ready(time.time() + BOOT_SECS, proc)
        if not_ready:
            tail(boot_log)
            return f"{not_ready} (boot log above)"
        log(f"backend ready on {BASE} — running the suite")
        # VEYQ_GEOCODER is dropped rather than inherited: set, memoryLocation.e2e makes a real third-party
        # Nominatim call, which is a flake source in CI and reaches the public internet from a harness meant to
        # run self-hosted. Unset, exactly one assertion drops and every assertion about veyq still runs. To
        # exercise that leg, run the file by hand with the variable set.
        env = {k: v for k, v in os.environ.items() if k != "VEYQ_GEOCODER"}
        env.update({"VEYQ_E2E": "1", "VEYQ_BASE": BASE, "VEYQ_HOST": HOST, "VEYQ_STAMP": stamp})
        result = subprocess.run(
            ["npx", "vitest", "run", rel, "--testTimeout", TEST_MS],
            cwd=str(WEB), env=env,
        )
        if result.returncode != 0:
            return f"the suite failed (vitest exit {result.returncode})"
        return None
    finally:
        kill(proc)


def main(argv: list[str]) -> int:
    if not shutil.which("docker"):
        raise SystemExit("[e2e] docker is required (the bundled stack supplies the DB + object store)")
    if not JAR.is_file():
        raise SystemExit(f"[e2e] backend jar missing: {JAR}\nBuild it first: (cd backend && ./gradlew quarkusBuild)")

    files = [normalize(a) for a in argv] if argv else discover()
    if port_is_taken():
        raise SystemExit(
            f"[e2e] port {PORT} is already serving — another backend (an IntelliJ quarkusDev?) is running. "
            f"Stop it, or set VEYQ_PORT to a free port."
        )

    compose_up()
    # One stamp for the whole run: it varies emails/usernames so a re-run against a warm limiter sees a new
    # invite recipient, and the per-file database makes cross-file collisions impossible anyway. Digits only —
    # the username pattern is [A-Za-z0-9_.-] with a 32-char cap.
    stamp = str(int(time.time()))
    log(f"running {len(files)} e2e file(s), one fresh backend each, stamp={stamp}")

    failures: list[tuple[str, str]] = []
    with tempfile.TemporaryDirectory(prefix="veyq-e2e-") as tmp:
        workdir = Path(tmp)
        for index, path in enumerate(files, start=1):
            reason = run_file(index, path, workdir, stamp)
            if reason:
                # Never short-circuit: one broken suite must not hide the state of the others.
                failures.append((path.relative_to(WEB).as_posix(), reason))

    print("", flush=True)
    log("==================== summary ====================")
    log(f"total: {len(files)}  passed: {len(files) - len(failures)}  failed: {len(failures)}")
    for name, reason in failures:
        log(f"  FAILED  {name}: {reason}")
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except NoE2eFilesError as e:
        raise SystemExit(f"[e2e] NO FILES MATCHED: {e}") from e
