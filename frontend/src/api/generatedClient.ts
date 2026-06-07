import { apiError, apiFetchUrl, API_BASE } from '../shared/api/httpClient'
import {
  AuthApi,
  Configuration,
  FoldersApi,
  FrontendApi,
  GroupsApi,
  MemoriesApi,
  MemoryMediaApi,
  MemoryParticipantsApi,
  MemoryPlaylistsApi,
  MemorySharesApi,
  MemoryTracksApi,
  ProfilesApi,
  PublicSharesApi,
  SearchApi,
  ServerApi,
  SettingsApi,
  SetupApi,
  TenantRegistrationInvitesApi,
  TenantsApi,
  UsersApi
} from './generated'

const fetchApi: typeof fetch = (url, init) => apiFetchUrl(String(url), init)
const generatedConfiguration = new Configuration({ basePath: API_BASE, fetchApi })

export const authApi = new AuthApi(generatedConfiguration)
export const setupApi = new SetupApi(generatedConfiguration)
export const usersApi = new UsersApi(generatedConfiguration)
export const tenantsApi = new TenantsApi(generatedConfiguration)
export const tenantRegistrationInvitesApi = new TenantRegistrationInvitesApi(generatedConfiguration)
export const groupsApi = new GroupsApi(generatedConfiguration)
export const foldersApi = new FoldersApi(generatedConfiguration)
export const memoriesApi = new MemoriesApi(generatedConfiguration)
export const memoryMediaApi = new MemoryMediaApi(generatedConfiguration)
export const memoryParticipantsApi = new MemoryParticipantsApi(generatedConfiguration)
export const memoryPlaylistsApi = new MemoryPlaylistsApi(generatedConfiguration)
export const memorySharesApi = new MemorySharesApi(generatedConfiguration)
export const memoryTracksApi = new MemoryTracksApi(generatedConfiguration)
export const profilesApi = new ProfilesApi(generatedConfiguration)
export const publicSharesApi = new PublicSharesApi(generatedConfiguration)
export const searchApi = new SearchApi(generatedConfiguration)
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
