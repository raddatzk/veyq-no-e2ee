import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Same-origin app: the client talks to `/api/...` on the host that served the page. In dev, Vite proxies
// `/api` to the backend (override the target with VEYQ_API_TARGET) so the operator-blind contract is exercised
// against the real server, not a mock.
const apiTarget = process.env.VEYQ_API_TARGET ?? 'http://127.0.0.1:8080'
// Dev server port — override with VITE_PORT (e.g. from the IntelliJ "Web (Vite)" run config). Vite's
// default 5173 is the fallback.
const port = Number(process.env.VITE_PORT) || 5173

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
})
