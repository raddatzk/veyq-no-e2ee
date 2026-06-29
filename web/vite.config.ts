import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Same-origin app: the client talks to `/api/...` on the host that served the page. In dev, Vite proxies
// `/api` to the backend (override the target with VEYQ_API_TARGET) so the operator-blind contract is exercised
// against the real server, not a mock.
const apiTarget = process.env.VEYQ_API_TARGET ?? 'http://127.0.0.1:8080'
// Same-origin object store (ADR 0013 §4): a dev affordance so a browser can PUT/GET presigned media URLs
// without CORS. The backend signs them for THIS web origin (veyq.blob-store.public-endpoint=<this origin>),
// and Vite forwards `/<bucket>/…` to the store WITHOUT touching the Host, so SigV4 (signs host+path) still
// verifies. Only used when the dev backend runs against a real store (`--profile s3`).
const storeTarget = process.env.VEYQ_STORE_TARGET ?? 'http://localhost:19000'
// Optional: override the active-tenant Host (resolved server-side from Host) — for driving a seeded tenant
// whose domain isn't the proxy target host (e.g. a media live-smoke tenant). Unset in normal dev.
const smokeHost = process.env.VEYQ_SMOKE_HOST
// Dev server port — override with VITE_PORT (e.g. from the IntelliJ "Web (Vite)" run config). Vite's
// default 5173 is the fallback.
const port = Number(process.env.VITE_PORT) || 5173

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: !smokeHost,
        // When VEYQ_SMOKE_HOST is set, force the upstream Host so the server resolves that tenant.
        configure: smokeHost
          ? (proxy: any) => proxy.on('proxyReq', (r: any) => r.setHeader('host', smokeHost))
          : undefined,
      },
      // Forward presigned media URLs to the object store same-origin (no Host rewrite — SigV4 signs the Host).
      '/veyq': { target: storeTarget, changeOrigin: false },
    },
  },
})
