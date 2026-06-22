import type { ServerCapability } from './api'

type Translate = (key: string, values?: Record<string, unknown>) => string
type TranslationExists = (key: string) => boolean

function capabilityKey(capabilityId: string, field: 'title' | 'description'): string {
  return `capabilities.items.${capabilityId.replace(/[^A-Za-z0-9]/g, '_')}.${field}`
}

export function capabilityTitle(capability: ServerCapability, t: Translate, te: TranslationExists): string {
  const key = capabilityKey(capability.id, 'title')
  return te(key) ? t(key) : capability.id
}

export function capabilityDescription(capability: ServerCapability, t: Translate, te: TranslationExists): string {
  const key = capabilityKey(capability.id, 'description')
  return te(key) ? t(key) : t('capabilities.unknownDescription', { id: capability.id })
}
