import { generatedCall, profilesApi } from '../../../api/generatedClient'
import type { UserProfileSummary } from '../../../api/generated'

export type UserProfile = UserProfileSummary

export function getUserProfile(tenantName: string, userPermalink: string): Promise<UserProfile> {
  return generatedCall(profilesApi.getUserProfile({ tenantName, userPermalink }))
}
