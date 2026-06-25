export function routePathToMemoryPath(value: string | string[] | undefined): string {
  const parts = Array.isArray(value) ? value : value ? [value] : []
  const normalized = parts
    .flatMap((part) => String(part).split('/'))
    .filter(Boolean)
    .join('/')
  return normalized ? `/${normalized}` : '/'
}

export function memoryPathToTenantRoute(tenantName: string, path: string): string {
  const suffix = path && path !== '/' ? path : ''
  return suffix || '/'
}

export function tenantApiPath(tenantName: string, segment: string, suffix = ''): string {
  return `/api/${encodeURIComponent(tenantName)}/${segment}${suffix}`
}

export function pathQuery(path: string): string {
  return `path=${encodeURIComponent(path || '/')}`
}

export function isSettingsPath(path: string): boolean {
  return path.split('/').filter(Boolean).at(-1) === 'settings'
}
