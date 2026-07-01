// en.memory.date (web) — a Memory's curated date, sealed on the NODE under its `K_mem` (uc.memory.date-place,
// cr-0067). Operator-blind: the effective day range travels in one client-versioned JSON blob `wrappedMeta`
// `{ v, date: { mode, from?, to? }, place? }` (the place slice grows the same blob — no contract change),
// AEAD-sealed under `K_mem` (AAD context `node-meta`) so the server only ever holds ciphertext. The range is
// **materialized** for every mode — the browse card grid shows + sorts by it WITHOUT loading a Memory's media.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { contentApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { epochMsToIsoDate } from '../../shared/isoDate'
import { effectiveCapturedAt, unsealMeta, type MediaItem } from './media'
import { openContentKey, type TreeNode } from './tree'

/** AAD context binding the sealed node-metadata blob (`wrappedMeta`) to its purpose (cr-0067). */
const NODE_META_CTX = 'node-meta'

/** The four date modes: derive from media (`auto`), a single day, a `from..to` span, or an open-ended `since`. */
export type MemoryDateMode = 'auto' | 'single' | 'range' | 'ongoing'

/**
 * A Memory's effective day range. `single` ⇒ `from === to`; `range` ⇒ both; `ongoing` ⇒ `from`, no `to`; a
 * materialized `auto` ⇒ both (or neither when the Memory has no trustworthy dated media). Days are `YYYY-MM-DD`.
 */
export type MemoryDate = { mode: MemoryDateMode; from?: string; to?: string }

/** The client-versioned node-metadata blob. `place` (a later slice) rides the same blob — no contract change. */
type WrappedMeta = { v: number; date?: MemoryDate; place?: unknown }

const MODES: readonly MemoryDateMode[] = ['auto', 'single', 'range', 'ongoing']
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/

const isoDayOrUndef = (v: unknown): string | undefined => (typeof v === 'string' && ISO_DAY.test(v) ? v : undefined)

/** Normalize a loosely-typed date payload to a valid [MemoryDate], or null when the mode is unrecognized. */
function normalizeDate(raw: unknown): MemoryDate | null {
  if (!raw || typeof raw !== 'object') return null
  const d = raw as { mode?: unknown; from?: unknown; to?: unknown }
  if (typeof d.mode !== 'string' || !MODES.includes(d.mode as MemoryDateMode)) return null
  return { mode: d.mode as MemoryDateMode, from: isoDayOrUndef(d.from), to: isoDayOrUndef(d.to) }
}

/** Seal a Memory's date into the opaque `wrappedMeta` blob under `nodeKey` (`K_mem`) → base64. */
export function sealMemoryDate(nodeKey: Uint8Array, date: MemoryDate): string {
  const blob: WrappedMeta = { v: 1, date }
  const bytes = new TextEncoder().encode(JSON.stringify(blob))
  return b64std(v1.symWrap(nodeKey, bytes, NODE_META_CTX, v1.randomBytes(12)))
}

/**
 * Unseal the Memory date from a node's `wrappedMeta` under `nodeKey` (`K_mem`). Best-effort (E3): null when the
 * node carries no meta, the wrap doesn't open, the JSON is malformed, or the mode is unrecognized — the header
 * then falls back to the neutral `auto`/no-date state rather than break.
 */
export function unsealMemoryDate(nodeKey: Uint8Array, node: { wrappedMeta: string | null }): MemoryDate | null {
  if (!node.wrappedMeta) return null
  try {
    const json = new TextDecoder().decode(v1.symUnwrap(nodeKey, fromB64std(node.wrappedMeta), NODE_META_CTX))
    const parsed = JSON.parse(json) as WrappedMeta
    return normalizeDate(parsed?.date)
  } catch {
    return null
  }
}

/**
 * Re-seal `date` under `nodeKey` (`K_mem`) and PATCH it onto the node via the partial `updateNode` (cr-0067).
 * Returns the sealed blob so the caller can update its local `node.wrappedMeta` (and re-render the card/header)
 * without a re-list. (The place slice will merge `place` into the same blob here.)
 */
export async function setMemoryDate(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  date: MemoryDate,
): Promise<string> {
  const wrappedMeta = sealMemoryDate(nodeKey, date)
  await withRefresh(() => contentApi().updateNode({ scopeId, nodeId, updateNodeRequest: { wrappedMeta } }))
  return wrappedMeta
}

/**
 * Materialize a Memory's `auto` range from its **own** media, day-truncated (local `YYYY-MM-DD`). Counts only
 * **trustworthy** capture times — a user `capturedAtOverride`, or an EXIF-sourced `capturedAt` — so a
 * file-modification-date fallback can't stretch the range (cr-0067). An empty result ⇒ `{ mode: 'auto' }` (the
 * "no date yet" state). `nodeKey` = the Memory's `K_mem` (each media's capture-time tier is sealed under it).
 */
export function computeAutoRange(items: MediaItem[], nodeKey: Uint8Array): MemoryDate {
  const days: number[] = []
  for (const item of items) {
    const tier = unsealMeta(nodeKey, item)
    if (!tier) continue
    const trustworthy = tier.capturedAtOverride !== undefined || tier.capturedAtSource === 'exif'
    const ms = effectiveCapturedAt(tier)
    if (trustworthy && ms !== undefined) days.push(ms)
  }
  if (days.length === 0) return { mode: 'auto' }
  return { mode: 'auto', from: epochMsToIsoDate(Math.min(...days)), to: epochMsToIsoDate(Math.max(...days)) }
}

/** The start day used to sort the card grid (newest first, undated last) — a materialized/manual `from`. */
export function startDayOf(date: MemoryDate | null | undefined): string | undefined {
  return date?.from
}

/** Whether two date ranges are equal (mode + both ends) — the reconcile re-seals only when this is false. */
export function sameMemoryDate(a: MemoryDate | null, b: MemoryDate | null): boolean {
  return a?.mode === b?.mode && a?.from === b?.from && a?.to === b?.to
}

/**
 * A card node's own date for the browse grid (cr-0067): open its `K_mem` under `parentKey` (`K_SC` for a
 * top-level Memory, the parent Memory's `K_mem` for a chapter), then unseal its `wrappedMeta`. Best-effort:
 * null when the key/meta is absent or won't open — **without loading the Memory's media**.
 */
export function unsealNodeDate(parentKey: Uint8Array | null, node: TreeNode): MemoryDate | null {
  if (!parentKey) return null
  try {
    const key = openContentKey(parentKey, node)
    return key ? unsealMemoryDate(key, node) : null
  } catch {
    return null // a key that won't open (wrong parent / malformed wrap) → no date, never a throw
  }
}

/**
 * Order memory/chapter nodes for the card grid: by **start day, newest first, undated last** (stable within a
 * group). Each node's date is unsealed under `parentKey`; the result pairs every node with its date so the card
 * can show the range without a second unseal. Reads only `wrappedMeta` — never a Memory's media.
 */
export function sortNodesByDate(parentKey: Uint8Array | null, nodes: TreeNode[]): { node: TreeNode; date: MemoryDate | null }[] {
  return nodes
    .map((node, i) => ({ node, i, date: unsealNodeDate(parentKey, node) }))
    .sort((a, b) => {
      const da = a.date?.from
      const db = b.date?.from
      if (!da && !db) return a.i - b.i // both undated → stable list order, undated grouped last
      if (!da) return 1
      if (!db) return -1
      return da < db ? 1 : da > db ? -1 : a.i - b.i // larger ISO day (later) first
    })
    .map(({ node, date }) => ({ node, date }))
}
