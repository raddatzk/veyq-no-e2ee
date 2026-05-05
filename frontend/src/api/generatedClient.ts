import { apiError, apiFetchUrl, API_BASE } from '../shared/api/httpClient'
import {
  AlbumsApi,
  AuthApi,
  Configuration,
  ExplorerApi,
  FoldersApi,
  FrontendApi,
  GroupsApi,
  MediaApi,
  MediaUploadsApi,
  ServerApi,
  SettingsApi,
  SetupApi,
  TenantsApi,
  UsersApi
} from './generated'

const fetchApi: typeof fetch = (url, init) => apiFetchUrl(String(url), init)
const generatedConfiguration = new Configuration({ basePath: API_BASE, fetchApi })

export const authApi = new AuthApi(generatedConfiguration)
export const setupApi = new SetupApi(generatedConfiguration)
export const usersApi = new UsersApi(generatedConfiguration)
export const tenantsApi = new TenantsApi(generatedConfiguration)
export const groupsApi = new GroupsApi(generatedConfiguration)
export const explorerApi = new ExplorerApi(generatedConfiguration)
export const foldersApi = new FoldersApi(generatedConfiguration)
export const albumsApi = new AlbumsApi(generatedConfiguration)
export const mediaApi = new MediaApi(generatedConfiguration)
export const mediaUploadsApi = new MediaUploadsApi(generatedConfiguration)
export const serverApi = new ServerApi(generatedConfiguration)
export const settingsApi = new SettingsApi(generatedConfiguration)
export const frontendApi = new FrontendApi(generatedConfiguration)

export async function generatedCall<T>(request: Promise<T>): Promise<T> {
  try {
    return await request
  } catch (error) {
    if (isGeneratedResponseError(error)) {
      throw await apiError(error.response)
    }
    throw error
  }
}

function isGeneratedResponseError(error: unknown): error is { response: Response } {
  return (
    typeof error === 'object' &&
    error !== null &&
    'response' in error &&
    error.response instanceof Response
  )
}
