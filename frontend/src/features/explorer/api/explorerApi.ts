import { explorerApi, generatedCall } from '../../../api/generatedClient'
import type { ExplorerResponse } from './types'

export function getExplorer(tenantName: string, path: string): Promise<ExplorerResponse> {
  return generatedCall(explorerApi.getExplorer({ tenantName, path }))
}
