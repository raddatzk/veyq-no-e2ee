import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite dev-server config. `npm run dev` works with the defaults below; the env vars only matter for special
// setups (the IntelliJ run configs, the media live-smoke). In production the built app is served elsewhere, so
// none of this applies there.

// `/api` is proxied to the backend. Override the backend URL with VEYQ_API_TARGET.
const apiTarget = process.env.VEYQ_API_TARGET ?? 'http://127.0.0.1:8080'
// `/veyq` (presigned media) is proxied to the object store — only used with the s3 store profile.
const storeTarget = process.env.VEYQ_STORE_TARGET ?? 'http://localhost:19000'
// Dev-server port (the IntelliJ run config sets VITE_PORT). Default 5173.
const port = Number(process.env.VITE_PORT) || 5173
// Force one fixed tenant Host regardless of the browser URL (used by the media live-smoke). Usually unset.
const smokeHost = process.env.VEYQ_SMOKE_HOST

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port,
    // Allow browsing the dev server under `*.localhost` — one subdomain per local tenant. Add other suffixes here.
    allowedHosts: ['.localhost'],
    proxy: {
      // The backend picks the tenant from the request Host. `changeOrigin: false` forwards the REAL browser Host,
      // so browsing `workspace-a.localhost` resolves tenant a, `workspace-b.localhost` tenant b. The port is
      // stripped server-side, so the hostname is the identity. (VEYQ_SMOKE_HOST overrides this with a fixed host.)
      '/api': {
        target: apiTarget,
        changeOrigin: false,
        configure: smokeHost
          ? (proxy: any) => proxy.on('proxyReq', (req: any) => req.setHeader('host', smokeHost))
          : undefined,
      },
      // Presigned media → object store, Host left untouched (SigV4 signs the Host).
      '/veyq': { target: storeTarget, changeOrigin: false },
    },
  },
})
