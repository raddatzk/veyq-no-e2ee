import { apiError, apiFetchUrl, API_BASE } from '../shared/api/httpClient'
import { AuthApi, Configuration as AuthConfiguration } from './generated/auth'
import { ExplorerApi, Configuration as ExplorerConfiguration } from './generated/explorer'
import { GroupsApi, Configuration as GroupsConfiguration } from './generated/groups'
import {
  AlbumsApi,
  Configuration as LibraryConfiguration,
  FoldersApi,
  MediaApi,
  MediaUploadsApi
} from './generated/library'
import { ServerApi, Configuration as ServerConfiguration } from './generated/server'
import { SettingsApi, Configuration as SettingsConfiguration } from './generated/settings'
import { SetupApi, Configuration as SetupConfiguration } from './generated/setup'
import { TenantsApi, Configuration as TenantsConfiguration } from './generated/tenants'
import { UsersApi, Configuration as UsersConfiguration } from './generated/users'

const fetchApi: typeof fetch = (url, init) => apiFetchUrl(String(url), init)

export const authApi = new AuthApi(new AuthConfiguration({ basePath: API_BASE, fetchApi }))
export const setupApi = new SetupApi(new SetupConfiguration({ basePath: API_BASE, fetchApi }))
export const usersApi = new UsersApi(new UsersConfiguration({ basePath: API_BASE, fetchApi }))
export const tenantsApi = new TenantsApi(new TenantsConfiguration({ basePath: API_BASE, fetchApi }))
export const groupsApi = new GroupsApi(new GroupsConfiguration({ basePath: API_BASE, fetchApi }))
export const explorerApi = new ExplorerApi(new ExplorerConfiguration({ basePath: API_BASE, fetchApi }))
export const foldersApi = new FoldersApi(new LibraryConfiguration({ basePath: API_BASE, fetchApi }))
export const albumsApi = new AlbumsApi(new LibraryConfiguration({ basePath: API_BASE, fetchApi }))
export const mediaApi = new MediaApi(new LibraryConfiguration({ basePath: API_BASE, fetchApi }))
export const mediaUploadsApi = new MediaUploadsApi(
  new LibraryConfiguration({ basePath: API_BASE, fetchApi })
)
export const serverApi = new ServerApi(new ServerConfiguration({ basePath: API_BASE, fetchApi }))
export const settingsApi = new SettingsApi(
  new SettingsConfiguration({ basePath: API_BASE, fetchApi })
)

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
