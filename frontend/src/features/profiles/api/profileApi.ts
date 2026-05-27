import { generatedCall, profilesApi } from '../../../api/generatedClient'
import type { UserProfileSummary } from '../../../api/generated'
import type { Memory } from '../../memories/api'

export type UserProfile = Omit<UserProfileSummary, 'memories'> & {
  memories: Memory[]
}

export function getUserProfile(tenantName: string, userPermalink: string): Promise<UserProfile> {
  return generatedCall(profilesApi.getUserProfile({ tenantName, userPermalink })).then((profile) => ({
    ...profile,
    memories: profile.memories.map((memory) => ({
      ...memory,
      parentId: memory.parentId ?? null,
      coverMediaId: memory.coverMediaId ?? null,
      coverUrl: memory.coverUrl ?? null,
      coverCrop: memory.coverCrop ?? null,
      favorite: memory.favorite ?? false,
      scope: memory.scope ?? null
    }))
  }))
}
