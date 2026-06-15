// Group gallery media into day buckets by capture date (takenAt, a low-sensitivity timestamp the server
// already orders by). Each bucket gets its own justified layout so the gallery reads as a dated timeline,
// Apple-Photos style. Pure + locale-aware so all three platforms can mirror the same grouping.

/** Local calendar-day key (YYYY-MM-DD) for an ISO timestamp. */
export function dayKey(takenAt: string): string {
  const d = new Date(takenAt)
  if (Number.isNaN(d.getTime())) return '—'
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** Split items (already ordered by takenAt) into consecutive day buckets, preserving order. */
export function groupByDay<T extends { takenAt: string }>(items: T[]): { key: string; items: T[] }[] {
  const buckets: { key: string; items: T[] }[] = []
  for (const item of items) {
    const key = dayKey(item.takenAt)
    const last = buckets[buckets.length - 1]
    if (last && last.key === key) last.items.push(item)
    else buckets.push({ key, items: [item] })
  }
  return buckets
}

/** A bucket's header label: the full localized date (e.g. "13. Juni 2026"). */
export function formatDayLabel(key: string, locale: string): string {
  if (key === '—') return '—'
  const d = new Date(`${key}T00:00:00`)
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long', year: 'numeric' }).format(d)
}

/** The detail subtitle: a single date, or a "start – end" range across all media. */
export function formatDateRange(items: { takenAt: string }[], locale: string): string {
  const keys = items.map((i) => dayKey(i.takenAt)).filter((k) => k !== '—').sort()
  if (!keys.length) return ''
  const first = keys[0]
  const last = keys[keys.length - 1]
  if (first === last) return formatDayLabel(first, locale)
  return `${formatDayLabel(first, locale)} – ${formatDayLabel(last, locale)}`
}
