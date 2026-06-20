import { defineConfig, devices } from '@playwright/test'

// E2E against a REAL backend (the E2EE crypto/relay flows can't be meaningfully mocked). Playwright boots a
// fresh in-memory backend (H2, in-process blob store — no S3/MariaDB) on :8099 and the vite dev server on
// :8088 proxying to it. Isolated ports so a developer's own veyq instance (commonly :8080/:8081) is untouched.
// Serial + single worker: the backend is a shared instance that is set up exactly once per run.
const BACKEND_PORT = 8099
const FRONTEND_PORT = 8088

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: `http://127.0.0.1:${FRONTEND_PORT}`,
    locale: 'de-DE',
    trace: 'on-first-retry'
  },
  webServer: [
    {
      // Fresh in-memory backend (no reuse → deterministic setupRequired state at the start of each run).
      command: `cd ../backend && DB_KIND=h2 DB_URL='jdbc:h2:mem:veyq;DB_CLOSE_DELAY=-1' DB_USER=sa DB_PASSWORD= ./gradlew quarkusDev --console=plain -Dquarkus.http.port=${BACKEND_PORT}`,
      url: `http://127.0.0.1:${BACKEND_PORT}/api/setup`,
      reuseExistingServer: false,
      timeout: 300_000
    },
    {
      command: `VITE_PORT=${FRONTEND_PORT} VITE_PROXY_TARGET=http://127.0.0.1:${BACKEND_PORT} npm run dev`,
      url: `http://127.0.0.1:${FRONTEND_PORT}`,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000
    }
  ],
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
