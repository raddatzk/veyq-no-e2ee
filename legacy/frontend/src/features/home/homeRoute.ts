// Member-facing path URLs for the home app (distinct from public `/s/<id>` shares — these require holding
// the scope key). The first segment selects the space; the rest is the folder path, optionally ending on a
// memory:
//   tenant   → /urlaube/italien            (no prefix — the tenant is the default context)
//   group    → /@reisegruppe/urlaube/italien   (@<group name slug>)
//   personal → /~me/urlaube/italien        (~me)
// A member can copy such a URL and send it to another member, who opens the exact memory (web or, via the
// banner deep link, the app) with a correct back-stack — no public share needed.
import { slugify } from '../memory/curate/curate'
import type { SpaceEntry } from '../spaces/switchActiveSpace/switchActiveSpace'

export type HomeRoute = { kind: 'PERSONAL' | 'TENANT' | 'GROUP'; groupSlug?: string; segments: string[] }

const PERSONAL_PREFIX = '~me'

/** The first path segment for a space (empty for a tenant — it's the default, prefix-less context). */
function spacePrefix(entry: SpaceEntry): string {
  if (entry.kind === 'PERSONAL') return PERSONAL_PREFIX
  if (entry.kind === 'GROUP') return '@' + slugify(entry.displayName || 'group')
  return '' // TENANT
}

/** Build the URL path for a location: space prefix + folder slugs + an optional trailing memory slug. */
export function buildHomePath(entry: SpaceEntry, folderSlugs: string[], memorySlug?: string): string {
  // Slugs + the @/~me prefixes are already URL-safe (slugify → [a-z0-9-]); no percent-encoding needed.
  const parts = [spacePrefix(entry), ...folderSlugs, memorySlug ?? ''].filter((s) => s.length > 0)
  return '/' + parts.join('/')
}

/** Parse a pathname into a space selector + the remaining folder/memory slug segments, or null for `/`. */
export function parseHomePath(pathname: string): HomeRoute | null {
  const raw = pathname.split('/').map(decodeURIComponent).filter((s) => s.length > 0)
  if (raw.length === 0) return null
  if (raw[0] === PERSONAL_PREFIX) return { kind: 'PERSONAL', segments: raw.slice(1) }
  if (raw[0].startsWith('@')) return { kind: 'GROUP', groupSlug: raw[0].slice(1), segments: raw.slice(1) }
  return { kind: 'TENANT', segments: raw }
}

/**
 * Resolve a parsed route to the matching space in the roster. The prefix-less TENANT path is resolved by the
 * current host (tenant = its domain), so the right tenant is picked even when the user belongs to several;
 * falls back to the first tenant (single-tenant / no-domain dev). Group = by its name slug.
 */
export function matchSpace(spaces: SpaceEntry[], route: HomeRoute, host?: string): SpaceEntry | undefined {
  if (route.kind === 'PERSONAL') return spaces.find((s) => s.kind === 'PERSONAL')
  if (route.kind === 'GROUP') return spaces.find((s) => s.kind === 'GROUP' && slugify(s.displayName || '') === route.groupSlug)
  return spaces.find((s) => s.kind === 'TENANT' && host !== undefined && s.domain === host)
    ?? spaces.find((s) => s.kind === 'TENANT')
}
