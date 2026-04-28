import { callApi } from '../../../shared/api/httpClient'
import { pathQuery } from '../../../shared/utils/paths'
import type { ExplorerResponse } from './types'

function explorerPath(tenantName: string, suffix = ''): string {
  return `/api/${encodeURIComponent(tenantName)}/explorer${suffix}`
}

export function getExplorer(tenantName: string, path: string): Promise<ExplorerResponse> {
  return callApi(explorerPath(tenantName, `?${pathQuery(path)}`))
}
