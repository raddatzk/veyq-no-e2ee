import http from 'node:http'
import { Server } from '@hocuspocus/server'
import { BackendClient } from './backendClient.js'
import { createDocumentRegistry } from './documentRegistry.js'

const port = Number(process.env.PORT || 8082)
const debounceMs = Number(process.env.JOURNAL_SNAPSHOT_DEBOUNCE_MS || 750)
const idleEvictionMs = Number(process.env.JOURNAL_DOCUMENT_IDLE_EVICTION_MS || 300000)
if (!process.env.JOURNAL_LIVE_SIDECAR_SECRET) {
  throw new Error('JOURNAL_LIVE_SIDECAR_SECRET must be set when the collaboration sidecar is enabled')
}
const backend = new BackendClient()
const documents = createDocumentRegistry({ backend, debounceMs, idleEvictionMs })

const hocuspocus = new Server({
  name: 'veyq-journal-collaboration',
  address: '0.0.0.0',
  port,

  onAuthenticate: documents.authenticate,

  connected: documents.connected,

  onLoadDocument: documents.loadDocument,

  onStoreDocument: documents.storeDocument,

  onDisconnect: documents.disconnect
})

const health = http.createServer((request, response) => {
  if (request.url === '/health') {
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ status: 'ok' }))
    return
  }
  response.writeHead(404)
  response.end()
})

health.listen(Number(process.env.HEALTH_PORT || 8083), '0.0.0.0')
hocuspocus.listen()
