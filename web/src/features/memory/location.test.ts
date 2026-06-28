import { describe, it, expect } from 'vitest'
import { parseHash, buildHash, resolveSlugPath, slugPathOf, hashForNode, PERSONAL_SCOPE } from './location'
import { NodeKind } from '../../api/generated/content'
import type { TreeNode } from './tree'

const node = (id: string, name: string, parentId: string | null, kind: NodeKind = NodeKind.Folder): TreeNode => ({
  id, name, parentId, kind, orderIndex: 0, wrappedContentKey: null,
})

// ~me / Reisen 2026 / Rom
const tree: TreeNode[] = [
  node('f1', 'Reisen 2026', null),
  node('m1', 'Rom', 'f1', NodeKind.Memory),
  node('f2', 'Sommer', 'f1'),
]

describe('parseHash', () => {
  it('defaults empty/# to the personal scope root', () => {
    expect(parseHash('')).toEqual({ scope: PERSONAL_SCOPE, segments: [] })
    expect(parseHash('#')).toEqual({ scope: PERSONAL_SCOPE, segments: [] })
    expect(parseHash('#~me')).toEqual({ scope: PERSONAL_SCOPE, segments: [] })
  })

  it('splits a scope token from the slug segments', () => {
    expect(parseHash('#~me/reisen-2026/rom')).toEqual({ scope: '~me', segments: ['reisen-2026', 'rom'] })
    expect(parseHash('#@familie/sommer')).toEqual({ scope: '@familie', segments: ['sommer'] })
  })

  it('treats a bare path (no scope token) as personal', () => {
    expect(parseHash('#reisen-2026')).toEqual({ scope: PERSONAL_SCOPE, segments: ['reisen-2026'] })
  })
})

describe('buildHash + slugPathOf + hashForNode', () => {
  it('round-trips a node to its fragment', () => {
    expect(buildHash(PERSONAL_SCOPE, ['reisen-2026', 'rom'])).toBe('#~me/reisen-2026/rom')
    expect(slugPathOf(tree, 'm1')).toEqual(['reisen-2026', 'rom'])
    expect(hashForNode(tree, 'm1')).toBe('#~me/reisen-2026/rom')
    expect(hashForNode(tree, null)).toBe('#~me')
  })
})

describe('resolveSlugPath', () => {
  it('walks the slug path to a node id (level by level)', () => {
    expect(resolveSlugPath(tree, [])).toBeNull() // root
    expect(resolveSlugPath(tree, ['reisen-2026'])).toBe('f1')
    expect(resolveSlugPath(tree, ['reisen-2026', 'rom'])).toBe('m1')
  })

  it('returns false when a segment does not match', () => {
    expect(resolveSlugPath(tree, ['nope'])).toBe(false)
    expect(resolveSlugPath(tree, ['reisen-2026', 'venedig'])).toBe(false)
  })

  it('round-trips with hashForNode for every node', () => {
    for (const n of tree) {
      const { segments } = parseHash(hashForNode(tree, n.id))
      expect(resolveSlugPath(tree, segments)).toBe(n.id)
    }
  })
})
