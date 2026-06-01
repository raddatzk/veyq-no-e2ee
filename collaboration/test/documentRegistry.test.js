import test from 'node:test'
import assert from 'node:assert/strict'
import * as Y from 'yjs'
import { createDocumentRegistry } from '../src/documentRegistry.js'

function testTimers() {
  const timers = []
  return {
    timers,
    setTimer(callback) {
      const timer = { callback, cleared: false }
      timers.push(timer)
      return timer
    },
    clearTimer(timer) {
      timer.cleared = true
    },
    async runLast() {
      const timer = timers.at(-1)
      assert.ok(timer)
      assert.equal(timer.cleared, false)
      await timer.callback()
    }
  }
}

test('authenticates without counting a connection until the connected hook runs', async () => {
  const backend = {
    async validateSession() {
      return { markdown: 'Initial', expiresAt: '2999-01-01T00:00:00Z' }
    },
    async persistSnapshot() {}
  }
  const registry = createDocumentRegistry({ backend, ...testTimers() })

  const context = await registry.authenticate({ token: 'token', documentName: 'doc' })
  const document = await registry.loadDocument({ documentName: 'doc', context })

  assert.equal(registry.stateFor('doc').connections, 0)
  assert.equal(document.getText('markdown').toString(), 'Initial')

  registry.connected({ documentName: 'doc' })

  assert.equal(registry.stateFor('doc').connections, 1)
})

test('persists changed markdown once on disconnect and evicts idle documents', async () => {
  const persisted = []
  const timers = testTimers()
  const backend = {
    async validateSession() {
      return { markdown: 'Initial', expiresAt: '2999-01-01T00:00:00Z' }
    },
    async persistSnapshot(snapshot) {
      persisted.push(snapshot)
    }
  }
  const registry = createDocumentRegistry({ backend, ...timers })
  const context = await registry.authenticate({ token: 'token', documentName: 'doc' })
  const document = await registry.loadDocument({ documentName: 'doc', context })
  registry.connected({ documentName: 'doc' })
  document.getText('markdown').insert(document.getText('markdown').length, ' update')

  await registry.disconnect({ documentName: 'doc', document, context })

  assert.equal(persisted.length, 1)
  assert.equal(persisted[0].markdown, 'Initial update')
  assert.equal(registry.documentCount(), 1)

  await timers.runLast()

  assert.equal(registry.documentCount(), 0)
})

test('stores debounced snapshots with the latest document text', async () => {
  const persisted = []
  const timers = testTimers()
  const backend = {
    async validateSession() {
      return { markdown: '', expiresAt: '2999-01-01T00:00:00Z' }
    },
    async persistSnapshot(snapshot) {
      persisted.push(snapshot)
    }
  }
  const registry = createDocumentRegistry({ backend, ...timers })
  const document = new Y.Doc()
  document.getText('markdown').insert(0, 'Draft')

  registry.storeDocument({ documentName: 'doc', document, context: { token: 'token' } })
  document.getText('markdown').insert(5, ' saved')
  await timers.runLast()

  assert.equal(persisted.length, 1)
  assert.equal(persisted[0].markdown, 'Draft saved')
})
