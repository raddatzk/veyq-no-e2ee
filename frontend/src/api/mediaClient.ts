import { callApi } from './httpClient'

type MediaResponse = {
  media: unknown[]
}

export async function media(tenantName: string): Promise<unknown[]> {
  const response = await callApi<MediaResponse>(`/api/${tenantName}/media`)
  return response.media
}
