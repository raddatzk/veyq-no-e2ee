/// <reference types="vitest" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://localhost:8080'
const serverPort = Number(process.env.VITE_PORT || 8081)

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.test.ts'],
    setupFiles: ['src/test/setup.ts']
  },
  server: {
    host: '127.0.0.1',
    port: serverPort,
    proxy: {
      '/api': {
        target: proxyTarget,
        changeOrigin: true
      },
      // The live-journal relay WebSocket (operator-blind co-editing) lives outside /api.
      '/ws': {
        target: proxyTarget,
        changeOrigin: true,
        ws: true
      }
    }
  }
})
