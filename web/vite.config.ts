import { loadEnv, type Plugin } from 'vite'
// `vitest/config`'s defineConfig extends Vite's with the `test` block (setupFiles below) while staying a valid
// Vite config for `npm run dev`/`build`.
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { buildCsp, providerOrigin } from './src/shared/security/csp'
import { DEFAULT_GEOCODER_URL, DEFAULT_TILE_URL } from './src/shared/state/mapDefaults'

// Vite dev-server config. `npm run dev` works with the defaults below; the env vars only matter for special
// setups (the IntelliJ run configs, the media live-smoke). In production the built app is served elsewhere, so
// the dev proxy does not apply there — but the CSP (below) is baked into the built index.html, so it does.

// `/api` is proxied to the backend. Override the backend URL with VEYQ_API_TARGET.
const apiTarget = process.env.VEYQ_API_TARGET ?? 'http://127.0.0.1:8080'
// `/veyq` (presigned media) is proxied to the object store — only used with the s3 store profile.
const storeTarget = process.env.VEYQ_STORE_TARGET ?? 'http://localhost:19000'
// Dev-server port (the IntelliJ run config sets VITE_PORT). Default 5173.
const port = Number(process.env.VITE_PORT) || 5173
// Force one fixed tenant Host regardless of the browser URL (used by the media live-smoke). Usually unset.
const smokeHost = process.env.VEYQ_SMOKE_HOST

// Content-Security-Policy (cr-0133 / audit P2e). Injected as a <meta http-equiv> so it ships inside the static
// build with no server needed (self-hostable by default), and mirrored onto the dev server as a real header so
// dev == prod and violations surface while developing. The allowed map-provider origins are derived from the
// SAME config the app fetches from (VITE_VEYQ_MAP_* or the mapDefaults fallback), so a self-hosted tile/geocoder
// deployment stays covered automatically. frame-ancestors/HSTS are header-only and deferred to the future prod
// web-serving component — see docs/crosscutting/cc.security.csp.md.
function cspPlugin(env: Record<string, string>, dev: boolean): Plugin {
  const tileOrigin = providerOrigin(env.VITE_VEYQ_MAP_TILE_URL || DEFAULT_TILE_URL)
  const geocoderOrigin = providerOrigin(env.VITE_VEYQ_MAP_GEOCODER_URL || DEFAULT_GEOCODER_URL)
  const csp = buildCsp({ tileOrigin, geocoderOrigin, dev })
  return {
    name: 'veyq-csp',
    config: () => (dev ? { server: { headers: { 'Content-Security-Policy': csp } } } : {}),
    transformIndexHtml: (html) => ({
      html,
      tags: [
        {
          tag: 'meta',
          attrs: { 'http-equiv': 'Content-Security-Policy', content: csp },
          injectTo: 'head-prepend',
        },
      ],
    }),
  }
}

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue(), cspPlugin(env, command === 'serve')],
    test: {
      // Provide the PrimeVue toast service to every mounted component (cr-0157) — see src/test/setup.ts.
      setupFiles: ['./src/test/setup.ts'],
    },
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
  }
})
