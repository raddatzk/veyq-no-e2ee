# Local Web Smoke

Use this flow when you need reproducible local data for visual UI checks against the E2EE backend.

## Preconditions

- Backend is running in the Quarkus `dev` profile against the local dev database.
- Frontend is running on `http://127.0.0.1:8081` or `VEYQ_WEB_BASE` points at the active Vite port.
- The backend dev/test profiles enable `veyq.dev-tools.enabled=true`; production defaults it to `false`.

## Seed Data

```bash
cd frontend
npm run smoke:seed
```

`smoke:seed` creates a fresh local smoke account through `/api/dev/smoke/accounts`, then logs in and seeds
the personal space through the normal encrypted client/API paths. The dev endpoint only persists
client-generated account key material via the same `AccountFactory` used by setup and invitation
registration; Memory content, media bytes, journal text, tracks and playlists still go through E2EE.

The command writes `/tmp/veyq-seed.json` with the generated email, password and Secret Key for the UI smoke.
Override defaults when needed:

```bash
VEYQ_API_BASE=http://localhost:8080 \
VEYQ_SEED_PATH=/tmp/veyq-seed.json \
VEYQ_SMOKE_NAME="Codex Smoke" \
npm run smoke:seed
```

## Browser Smoke

With backend and frontend running:

```bash
cd frontend
npm run smoke:ui
```

The UI smoke reads `/tmp/veyq-seed.json`, signs in, checks Home and Gallery, captures screenshots in `/tmp`,
and verifies that the Memory ZIP contains valid PNG originals. It disables `showSaveFilePicker` in the
Playwright browser so the in-memory download fallback produces a deterministic download event.

Use a non-default Vite port with:

```bash
VEYQ_WEB_BASE=http://127.0.0.1:8082 npm run smoke:ui
```

## Notes

- Do not use direct database writes for routine local UI data. The dev endpoint exists to avoid duplicating
  persistence SQL while keeping the server blind.
- The generated account is disposable. Re-running the seed creates a new local account rather than trying to
  recover an old Secret Key from the server, because the server intentionally cannot store it.
