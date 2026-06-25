import { SharedArray } from 'k6/data'

const usersPath = __ENV.VEYQ_USERS_FILE || 'load/k6/users.local.json'

export const users = new SharedArray('veyq users', () => {
  const parsed = JSON.parse(open(usersPath))
  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new Error(`VEYQ_USERS_FILE must contain at least one user: ${usersPath}`)
  }
  parsed.forEach((user, index) => {
    if (!user.email || !user.password) {
      throw new Error(`User at index ${index} must include email and password`)
    }
  })
  return parsed
})

export const config = {
  baseUrl: requiredEnv('VEYQ_BASE_URL').replace(/\/+$/, ''),
  tenant: requiredEnv('VEYQ_TENANT'),
  runId: (__ENV.VEYQ_RUN_ID || `${Date.now()}`).toLowerCase().replace(/[^a-z0-9-]/g, '-'),
  profile: __ENV.VEYQ_PROFILE || 'smoke',
  vusMax: positiveInt(__ENV.VEYQ_VUS_MAX, 30),
  keepTestData: parseBoolean(__ENV.VEYQ_KEEP_TEST_DATA, false),
}

export function primaryUser() {
  return users[0]
}

export function vuUser() {
  const index = Math.max((__VU || 1) - 1, 0) % users.length
  return users[index]
}

function requiredEnv(name) {
  const value = __ENV[name]
  if (!value) throw new Error(`${name} is required`)
  return value
}

function parseBoolean(value, fallback) {
  if (value === undefined || value === null || value === '') return fallback
  return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase())
}

function positiveInt(value, fallback) {
  const parsed = Number(value || fallback)
  if (!Number.isFinite(parsed) || parsed < 1) return fallback
  return Math.floor(parsed)
}
