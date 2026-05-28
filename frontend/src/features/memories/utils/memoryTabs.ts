export const MEMORY_DETAIL_TABS = [
  'gallery',
  'map',
  'tracks',
  'playlists',
  'journal',
  'children',
  'buckets',
  'info'
] as const

export type MemoryDetailTab = (typeof MEMORY_DETAIL_TABS)[number]

export const DEFAULT_MEMORY_DETAIL_TAB: MemoryDetailTab = 'gallery'

export function isMemoryDetailTab(value: string): value is MemoryDetailTab {
  return MEMORY_DETAIL_TABS.includes(value as MemoryDetailTab)
}

export function memoryTabFromHash(hash: string): MemoryDetailTab {
  const value = hash.replace(/^#/, '')
  return isMemoryDetailTab(value) ? value : DEFAULT_MEMORY_DETAIL_TAB
}

export function memoryTabToHash(tab: MemoryDetailTab): string {
  return tab === DEFAULT_MEMORY_DETAIL_TAB ? '' : `#${tab}`
}
