import { apiError } from '../../shared/apiError'
import * as v1 from '../../shared/crypto/v1'
import { b64url, fromB64url } from '../../shared/base64'
import type { UnlockedSession } from '../account/login/login'

const textDecoder = new TextDecoder()

type NavigatorUserAgentData = {
  platform?: string
  model?: string
  brands?: Array<{ brand: string; version: string }>
  getHighEntropyValues?: (hints: string[]) => Promise<{ platform?: string; model?: string }>
}

export type LoginDeviceSummary = {
  credentialRef?: string
  name?: string
  lastLoginAt?: string
}

function userAgentData(): NavigatorUserAgentData | undefined {
  return (navigator as Navigator & { userAgentData?: NavigatorUserAgentData }).userAgentData
}

function isStandaloneApp(): boolean {
  const standaloneNavigator = navigator as Navigator & { standalone?: boolean }
  return standaloneNavigator.standalone === true || window.matchMedia?.('(display-mode: standalone)').matches === true
}

function browserName(): string {
  if (isStandaloneApp()) return 'veyq'
  const brands = userAgentData()?.brands ?? []
  const brand = brands.find((candidate) => !/Chromium|Not A Brand/i.test(candidate.brand))?.brand
  if (brand) return brand.replace('Microsoft Edge', 'Edge').replace('Google Chrome', 'Chrome')
  const ua = navigator.userAgent
  return /Firefox/.test(ua)
    ? 'Firefox'
    : /Edg\//.test(ua)
      ? 'Edge'
      : /Chrome/.test(ua)
        ? 'Chrome'
        : /Safari/.test(ua)
          ? 'Safari'
          : 'Browser'
}

function platformLabel(platform?: string, model?: string): string {
  if (model) return model
  const source = `${platform ?? ''} ${navigator.platform ?? ''} ${navigator.userAgent}`
  return /iPhone/.test(source)
    ? 'iPhone'
    : /iPad|Macintosh.*Mobile/.test(source)
      ? 'iPad'
      : /Android/.test(source)
        ? 'Android'
        : /Mac|macOS|Macintosh/.test(source)
          ? 'Mac'
          : /Win|Windows/.test(source)
            ? 'Windows PC'
            : /Linux/.test(source)
              ? 'Linux device'
            : 'Device'
}

function possessiveName(name?: string): string {
  const trimmed = name?.trim()
  if (!trimmed) return ''
  return trimmed.endsWith('s') ? `${trimmed}'` : `${trimmed}'s`
}

export async function defaultDeviceName(ownerName?: string): Promise<string> {
  const client = browserName()
  let platform = userAgentData()?.platform
  let model = userAgentData()?.model
  try {
    const values = await userAgentData()?.getHighEntropyValues?.(['platform', 'model'])
    platform = values?.platform ?? platform
    model = values?.model ?? model
  } catch {
    // Client hints are optional; the user agent fallback still gives a readable device class.
  }
  const device = platformLabel(platform, model)
  const owner = possessiveName(ownerName)
  return owner ? `${client} on ${owner} ${device}` : `${client} on ${device}`
}

export function fallbackDeviceName(): string {
  return `${browserName()} on ${platformLabel(userAgentData()?.platform, userAgentData()?.model)}`
}

function sealDeviceName(masterKey: Uint8Array, name: string): string {
  return b64url(v1.symWrap(masterKey, v1.utf8(name), 'login-device-name', v1.randomBytes(12)))
}

function openDeviceName(masterKey: Uint8Array, encryptedName: string): string {
  try {
    return textDecoder.decode(v1.symUnwrap(masterKey, fromB64url(encryptedName), 'login-device-name'))
  } catch {
    return ''
  }
}

export async function registerLoginDevice(
  apiBase: string,
  session: UnlockedSession,
): Promise<void> {
  try {
    const name = await defaultDeviceName(session.user.displayName)
    const res = await fetch(`${apiBase}/api/me/devices/${encodeURIComponent(session.deviceRef)}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
      body: JSON.stringify({ encryptedName: sealDeviceName(session.masterKey, name) }),
    })
    if (!res.ok) throw await apiError(res, 'device register')
  } catch {
    // Non-fatal: the user is logged in regardless of whether the device registry updated.
  }
}

export async function listLoginDevices(apiBase: string, session: UnlockedSession): Promise<LoginDeviceSummary[]> {
  const res = await fetch(`${apiBase}/api/me/devices`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw await apiError(res, 'login devices')
  const body: { devices: Array<{ deviceRef: string; encryptedName: string; lastLoginAt: string }> } = await res.json()
  return body.devices.map((device) => ({
    credentialRef: device.deviceRef,
    name: openDeviceName(session.masterKey, device.encryptedName) || fallbackDeviceName(),
    lastLoginAt: device.lastLoginAt,
  }))
}

export async function renameLoginDevice(
  apiBase: string,
  session: UnlockedSession,
  deviceRef: string,
  name: string,
): Promise<void> {
  const res = await fetch(`${apiBase}/api/me/devices/${encodeURIComponent(deviceRef)}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ encryptedName: sealDeviceName(session.masterKey, name) }),
  })
  if (!res.ok) throw await apiError(res, 'login device rename')
}

export async function removeLoginDevice(apiBase: string, sessionToken: string, deviceRef: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/me/devices/${encodeURIComponent(deviceRef)}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${sessionToken}` },
  })
  if (!res.ok) throw await apiError(res, 'login device removal')
}
