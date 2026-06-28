import { describe, it, expect } from 'vitest'
import { childrenOf, breadcrumbOf, nodeById } from './browse'
import { NodeKind } from '../../api/generated/content'
import type { TreeNode } from './tree'

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return { parentId: null, orderIndex: 0, name: over.id, wrappedContentKey: null, ...over }
}

// A small tree: root has folder "Trips" + memory "Solo"; "Trips" holds memory "Italy" + folder "2025".
const tree: TreeNode[] = [
  node({ id: 'trips', kind: NodeKind.Folder, name: 'Trips', orderIndex: 1 }),
  node({ id: 'solo', kind: NodeKind.Memory, name: 'Solo', orderIndex: 0 }),
  node({ id: 'italy', kind: NodeKind.Memory, name: 'Italy', parentId: 'trips' }),
  node({ id: '2025', kind: NodeKind.Folder, name: '2025', parentId: 'trips' }),
]

describe('childrenOf', () => {
  it('splits a level into folders + memories, ordered', () => {
    const root = childrenOf(tree, null)
    expect(root.folders.map((n) => n.name)).toEqual(['Trips'])
    expect(root.memories.map((n) => n.name)).toEqual(['Solo'])

    const inTrips = childrenOf(tree, 'trips')
    expect(inTrips.folders.map((n) => n.name)).toEqual(['2025'])
    expect(inTrips.memories.map((n) => n.name)).toEqual(['Italy'])
  })

  it('returns empty for a leaf', () => {
    expect(childrenOf(tree, 'solo')).toEqual({ folders: [], memories: [] })
  })
})

describe('breadcrumbOf', () => {
  it('walks root → ancestors → node', () => {
    expect(breadcrumbOf(tree, '2025', 'Personal')).toEqual([
      { id: null, name: 'Personal' },
      { id: 'trips', name: 'Trips' },
      { id: '2025', name: '2025' },
    ])
  })

  it('is just the root at the top level', () => {
    expect(breadcrumbOf(tree, null, 'Personal')).toEqual([{ id: null, name: 'Personal' }])
  })
})

describe('nodeById', () => {
  it('finds a node or returns null', () => {
    expect(nodeById(tree, 'italy')?.name).toBe('Italy')
    expect(nodeById(tree, null)).toBeNull()
    expect(nodeById(tree, 'nope')).toBeNull()
  })
})
