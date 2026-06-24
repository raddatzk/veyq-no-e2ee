import { getMemoryHeader, listChildMemories, listMemories, type Memory } from '../../memories/api'
import { apiError } from '../../../shared/apiError'
import { requireSession, session } from '../../../app/session'

export type UserProfile = {
  id: string
  displayName: string
  username: string
  initials: string
  avatarUrl?: string
  memories: Memory[]
}

export async function getUserProfile(tenantName: string, userUsername: string): Promise<UserProfile> {
  const s = requireSession()
  const res = await fetch(`${session.apiBase}/api/profiles/${encodeURIComponent(userUsername)}`, {
    headers: { authorization: `Bearer ${s.sessionToken}` },
  })
  if (!res.ok) throw await apiError(res, 'profile')
  const profile = await res.json()
  return { ...profile, memories: await listParticipantMemories(tenantName, profile) }
}

async function listParticipantMemories(tenantName: string, profile: Pick<UserProfile, 'id' | 'username'>): Promise<Memory[]> {
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
    if (header.participants.some((participant: { username?: string; permalink?: string; userId?: string | number }) => {
      const address = participant.username ?? participant.permalink
      return address === profile.username || String(participant.userId) === profile.id
    })) {
      matches.push(memory)
    }
    stack.push(...children)
  }

  return matches
}
