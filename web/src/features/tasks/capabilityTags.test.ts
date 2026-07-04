import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { GRANT_DELIVERY } from './capabilityTags'

// Conformance pin (cr-0076): the opaque tag the web client ships must equal the public registry
// (contracts/tasks/tags/v1.json), so a worker and the server never silently stop matching after a re-derive.
describe('capability-tag registry conformance', () => {
  it('grant-delivery matches the registry', () => {
    const path = fileURLToPath(new URL('../../../../contracts/tasks/tags/v1.json', import.meta.url))
    const registry = JSON.parse(readFileSync(path, 'utf8'))
    expect(GRANT_DELIVERY).toBe(registry.tags['grant-delivery'].tag)
  })
})
