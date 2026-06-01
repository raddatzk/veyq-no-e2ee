import test from 'node:test'
import assert from 'node:assert/strict'
import { Server } from '@hocuspocus/server'

test('uses the current Hocuspocus server constructor API', () => {
  assert.equal(typeof Server, 'function')
  assert.equal(typeof Server.configure, 'undefined')

  const server = new Server({ quiet: true })
  assert.equal(typeof server.listen, 'function')
})
