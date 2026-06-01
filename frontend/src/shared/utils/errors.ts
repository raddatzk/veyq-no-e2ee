import { translateErrorCode } from '../../i18n'
import { ApiError } from '../api/httpClient'

export function getErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return translateErrorCode(error.code) || error.fallbackMessage || fallback
  }
  return error instanceof Error && error.message ? error.message : fallback
}

export function isApiStatus(error: unknown, status: number): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'status' in error &&
    (error as { status?: unknown }).status === status
  )
}
