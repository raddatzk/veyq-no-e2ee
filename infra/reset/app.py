from __future__ import annotations

import base64
import http.client
import html
import json
import os
import posixpath
import secrets
import shutil
import socket
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional
from urllib.parse import parse_qs, quote

import pymysql


APP_TITLE = "veyq Test Reset"
EXCLUDED_TABLES = {"flyway_schema_history"}


class ConfigError(RuntimeError):
    pass


class ResetConfig:
    def __init__(self) -> None:
        self.username = required_env("RESET_USERNAME")
        self.password = required_env("RESET_PASSWORD")
        self.db_host = os.environ.get("DB_HOST", "veyq-db")
        self.db_port = int(os.environ.get("DB_PORT", "3306"))
        self.db_name = os.environ.get("DB_NAME", "veyq")
        self.db_user = os.environ.get("DB_USER", "veyq")
        self.db_password = required_env("DB_PASSWORD")
        self.media_storage_dir = safe_storage_root(
            os.environ.get("MEDIA_STORAGE_DIR", "/data/veyq-media")
        )
        self.restart_container = os.environ.get("RESET_RESTART_CONTAINER", "veyq").strip()
        self.docker_socket = Path(os.environ.get("DOCKER_SOCKET", "/var/run/docker.sock"))
        self.restart_timeout_seconds = int(os.environ.get("RESET_RESTART_TIMEOUT_SECONDS", "10"))


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ConfigError(f"{name} must be set")
    return value


def safe_storage_root(value: str) -> Path:
    root = Path(value).expanduser().resolve()
    if str(root) in {"", "/", "."}:
        raise ConfigError("MEDIA_STORAGE_DIR must not resolve to the filesystem root")
    return root


def db_connection(config: ResetConfig) -> pymysql.Connection:
    return pymysql.connect(
        host=config.db_host,
        port=config.db_port,
        user=config.db_user,
        password=config.db_password,
        database=config.db_name,
        charset="utf8mb4",
        autocommit=True,
    )


def quote_identifier(value: str) -> str:
    return "`" + value.replace("`", "``") + "`"


def reset_database(config: ResetConfig) -> list[str]:
    with db_connection(config) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select table_name
                from information_schema.tables
                where table_schema = %s
                  and table_type = 'BASE TABLE'
                order by table_name
                """,
                (config.db_name,),
            )
            table_names = [
                row[0]
                for row in cursor.fetchall()
                if row[0] not in EXCLUDED_TABLES
            ]
            cursor.execute("set foreign_key_checks = 0")
            try:
                for table_name in table_names:
                    cursor.execute(f"truncate table {quote_identifier(table_name)}")
            finally:
                cursor.execute("set foreign_key_checks = 1")
            return table_names


def reset_storage(root: Path) -> list[str]:
    root.mkdir(parents=True, exist_ok=True)
    resolved_root = root.resolve()
    if resolved_root != root:
        raise ConfigError("MEDIA_STORAGE_DIR changed while resolving")
    deleted: list[str] = []
    for child in sorted(root.iterdir(), key=lambda path: path.name):
        child_resolved = child.resolve() if not child.is_symlink() else child.absolute()
        try:
            child_resolved.relative_to(root)
        except ValueError:
            raise ConfigError(f"Refusing to delete path outside storage root: {child}")
        deleted.append(child.name)
        if child.is_symlink() or child.is_file():
            child.unlink()
        else:
            shutil.rmtree(child)
    return deleted


class DockerUnixConnection(http.client.HTTPConnection):
    def __init__(self, socket_path: Path, timeout: int = 10) -> None:
        super().__init__("localhost", timeout=timeout)
        self.socket_path = socket_path

    def connect(self) -> None:
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(str(self.socket_path))


def restart_container(config: ResetConfig) -> Optional[str]:
    if not config.restart_container:
        return None
    if not config.docker_socket.exists():
        raise ConfigError(f"Docker socket not found: {config.docker_socket}")

    connection = DockerUnixConnection(config.docker_socket, timeout=config.restart_timeout_seconds)
    path = (
        f"/containers/{quote(config.restart_container, safe='')}/restart"
        f"?t={config.restart_timeout_seconds}"
    )
    try:
        connection.request("POST", path, body=b"", headers={"Host": "localhost"})
        response = connection.getresponse()
        body = response.read().decode("utf-8", errors="replace")
    finally:
        connection.close()

    if response.status not in {204, 304}:
        message = body.strip() or response.reason
        raise RuntimeError(
            f"Failed to restart container {config.restart_container}: "
            f"HTTP {response.status} {message}"
        )
    return config.restart_container


def perform_reset(config: ResetConfig) -> dict[str, Any]:
    tables = reset_database(config)
    storage_entries = reset_storage(config.media_storage_dir)
    restarted_container = restart_container(config)
    return {
        "tables": tables,
        "storage_entries": storage_entries,
        "restarted_container": restarted_container,
        "table_count": len(tables),
        "storage_entry_count": len(storage_entries),
    }


def credentials_match(config: ResetConfig, username: str, password: str) -> bool:
    return secrets.compare_digest(username, config.username) and secrets.compare_digest(
        password, config.password
    )


def basic_auth_credentials(header_value: Optional[str]) -> Optional[tuple[str, str]]:
    if not header_value or not header_value.startswith("Basic "):
        return None
    try:
        decoded = base64.b64decode(header_value[6:], validate=True).decode("utf-8")
    except (ValueError, UnicodeDecodeError):
        return None
    if ":" not in decoded:
        return None
    username, password = decoded.split(":", 1)
    return username, password


def render_page(status: Optional[str] = None, details: Optional[dict[str, Any]] = None) -> bytes:
    details_html = ""
    if details:
        details_html = f"""
        <dl class="details">
          <dt>DB tables truncated</dt>
          <dd>{details["table_count"]}</dd>
          <dt>Storage entries deleted</dt>
          <dd>{details["storage_entry_count"]}</dd>
          <dt>Container restarted</dt>
          <dd>{html.escape(details.get("restarted_container") or "none")}</dd>
        </dl>
        """
    status_html = f'<p class="status">{html.escape(status)}</p>' if status else ""
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{APP_TITLE}</title>
  <style>
    :root {{
      color-scheme: light;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: #f4f6f8;
      color: #15202b;
    }}
    body {{
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      padding: 24px;
    }}
    main {{
      width: min(100%, 420px);
      background: #ffffff;
      border: 1px solid #d8dee6;
      border-radius: 8px;
      box-shadow: 0 14px 40px rgba(19, 32, 43, 0.12);
      padding: 28px;
    }}
    h1 {{
      margin: 0 0 20px;
      font-size: 24px;
      line-height: 1.2;
    }}
    label {{
      display: grid;
      gap: 6px;
      margin-bottom: 14px;
      font-size: 14px;
      font-weight: 650;
    }}
    input {{
      min-height: 42px;
      border: 1px solid #bcc7d1;
      border-radius: 6px;
      padding: 0 12px;
      font: inherit;
    }}
    button {{
      width: 100%;
      min-height: 44px;
      border: 0;
      border-radius: 6px;
      background: #b42318;
      color: white;
      font: inherit;
      font-weight: 750;
      cursor: pointer;
    }}
    button:hover {{
      background: #912018;
    }}
    .status {{
      border: 1px solid #c7d7fe;
      background: #eef4ff;
      border-radius: 6px;
      padding: 12px;
      margin: 0 0 16px;
    }}
    .details {{
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 8px 16px;
      margin: 16px 0 0;
      font-size: 14px;
    }}
    dt, dd {{
      margin: 0;
    }}
    dd {{
      font-weight: 750;
    }}
  </style>
</head>
<body>
  <main>
    <h1>{APP_TITLE}</h1>
    {status_html}
    <form method="post" action="/reset">
      <label>
        Username
        <input name="username" autocomplete="username" required>
      </label>
      <label>
        Password
        <input name="password" type="password" autocomplete="current-password" required>
      </label>
      <button type="submit">Reset test instance</button>
    </form>
    {details_html}
  </main>
</body>
</html>
""".encode("utf-8")


class ResetHandler(BaseHTTPRequestHandler):
    server_version = "veyq-reset/1.0"

    @property
    def config(self) -> ResetConfig:
        return self.server.config  # type: ignore[attr-defined]

    def do_GET(self) -> None:
        if self.path_only() == "/health":
            self.write_json(HTTPStatus.OK, {"status": "ok"})
            return
        if self.path_only() != "/":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        self.write_html(HTTPStatus.OK, render_page())

    def do_POST(self) -> None:
        if self.path_only() != "/reset":
            self.send_error(HTTPStatus.NOT_FOUND)
            return

        form = self.read_form()
        basic_credentials = basic_auth_credentials(self.headers.get("Authorization"))
        form_credentials = (
            form.get("username", [""])[0],
            form.get("password", [""])[0],
        )
        authorized = False
        if basic_credentials:
            authorized = credentials_match(self.config, *basic_credentials)
        if not authorized:
            authorized = credentials_match(self.config, *form_credentials)

        if not authorized:
            self.write_html(
                HTTPStatus.UNAUTHORIZED,
                render_page("Invalid username or password."),
                extra_headers={"WWW-Authenticate": 'Basic realm="veyq Reset"'},
            )
            return

        try:
            details = perform_reset(self.config)
        except Exception as exc:
            self.write_html(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                render_page(f"Reset failed: {exc}"),
            )
            return

        if self.wants_json():
            self.write_json(HTTPStatus.OK, {"status": "reset", **details})
            return
        self.write_html(HTTPStatus.OK, render_page("Test instance reset.", details))

    def path_only(self) -> str:
        return posixpath.normpath(self.path.split("?", 1)[0]) or "/"

    def read_form(self) -> dict[str, list[str]]:
        content_length = int(self.headers.get("Content-Length", "0") or "0")
        if content_length <= 0:
            return {}
        body = self.rfile.read(content_length).decode("utf-8")
        content_type = self.headers.get("Content-Type", "")
        if content_type.startswith("application/json"):
            try:
                payload = json.loads(body)
            except json.JSONDecodeError:
                return {}
            return {key: [str(value)] for key, value in payload.items()}
        return parse_qs(body, keep_blank_values=True)

    def wants_json(self) -> bool:
        accept = self.headers.get("Accept", "")
        return "application/json" in accept

    def write_html(
        self,
        status: HTTPStatus,
        body: bytes,
        extra_headers: Optional[dict[str, str]] = None,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def write_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:
        print(f"{self.address_string()} - {format % args}", flush=True)


class ResetServer(ThreadingHTTPServer):
    def __init__(self, server_address: tuple[str, int], config: ResetConfig) -> None:
        super().__init__(server_address, ResetHandler)
        self.config = config


def main() -> None:
    try:
        config = ResetConfig()
    except ConfigError as exc:
        print(f"Configuration error: {exc}", file=sys.stderr, flush=True)
        sys.exit(1)
    host = os.environ.get("RESET_HOST", "0.0.0.0")
    port = int(os.environ.get("RESET_PORT", "8081"))
    server = ResetServer((host, port), config)
    print(f"Serving reset UI on {host}:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
