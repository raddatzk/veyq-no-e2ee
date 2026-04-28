export function routePathToExplorerPath(value: string | string[] | undefined): string {
  const parts = Array.isArray(value) ? value : value ? [value] : []
  const normalized = parts
    .flatMap((part) => String(part).split('/'))
    .filter(Boolean)
    .join('/')
  return normalized ? `/${normalized}` : '/'
}

export function explorerPathToTenantRoute(tenantName: string, path: string): string {
  const suffix = path && path !== '/' ? path : ''
  return `/${tenantName}${suffix}`
}

export function tenantApiPath(tenantName: string, segment: string, suffix = ''): string {
  return `/api/${encodeURIComponent(tenantName)}/${segment}${suffix}`
}

export function pathQuery(path: string): string {
  return `path=${encodeURIComponent(path || '/')}`
}
