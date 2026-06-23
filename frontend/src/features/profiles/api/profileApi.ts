import { getMemoryHeader, listChildMemories, listMemories, type Memory } from '../../memories/api'
import { apiError } from '../../../shared/apiError'
import { requireSession, session } from '../../../app/session'

export type UserProfile = {
  id: string
  displayName: string
  permalink: string
  initials: string
  avatarUrl?: string
  memories: Memory[]
}

export async function getUserProfile(tenantName: string, userPermalink: string): Promise<UserProfile> {
  const s = requireSession()
  const res = await fetch(`${session.apiBase}/api/${encodeURIComponent(tenantName)}/profiles/${encodeURIComponent(userPermalink)}`, {
    headers: { authorization: `Bearer ${s.sessionToken}` },
  })
  if (!res.ok) throw await apiError(res, 'profile')
  const profile = await res.json()
  return { ...profile, memories: await listParticipantMemories(tenantName, profile) }
}

async function listParticipantMemories(tenantName: string, profile: Pick<UserProfile, 'id' | 'permalink'>): Promise<Memory[]> {
  const matches: Memory[] = []
  const seen = new Set<string>()
  const stack = [...await listMemories(tenantName)]

  while (stack.length) {
    const memory = stack.shift()
    if (!memory || seen.has(memory.id)) continue
    seen.add(memory.id)

    const [header, children] = await Promise.all([
      getMemoryHeader(tenantName, memory.id),
      listChildMemories(tenantName, memory.id),
    ])
    if (header.participants.some((participant) =>
      participant.permalink === profile.permalink || String(participant.userId) === profile.id
    )) {
      matches.push(memory)
    }
    stack.push(...children)
  }

  return matches
}
