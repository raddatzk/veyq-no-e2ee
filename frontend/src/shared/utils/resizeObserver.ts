export type RafResizeObserver = {
  observe: (element: Element) => void
  disconnect: () => void
}

export function createRafResizeObserver(
  onResize: (entry: ResizeObserverEntry, entries: ResizeObserverEntry[]) => void
): RafResizeObserver | null {
  if (typeof ResizeObserver === 'undefined') return null

  let resizeFrame: number | null = null
  const observer = new ResizeObserver((entries) => {
    const entry = entries.find(isVisibleEntry)
    if (!entry) return

    if (resizeFrame !== null) window.cancelAnimationFrame(resizeFrame)
    resizeFrame = window.requestAnimationFrame(() => {
      resizeFrame = null
      onResize(entry, entries)
    })
  })

  return {
    observe: (element: Element) => observer.observe(element),
    disconnect: () => {
      if (resizeFrame !== null) window.cancelAnimationFrame(resizeFrame)
      resizeFrame = null
      observer.disconnect()
    }
  }
}

function isVisibleEntry(entry: ResizeObserverEntry): boolean {
  return entry.contentRect.width > 0 && entry.contentRect.height > 0
}
