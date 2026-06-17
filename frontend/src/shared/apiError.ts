/**
 * Build a UI-safe Error from a failed fetch Response: a stable "<label> failed: <status>" message, with the
 * server's response body appended ONLY in dev builds. The backend is operator-blind so its error bodies carry
 * no secrets today, but client error text surfaces in the UI (and any future error reporting), so don't couple
 * it to whatever the server happens to echo. Mirrors the Android A5 cleanup. Throw the result:
 *   `if (!res.ok) throw await apiError(res, 'list tracks')`
 */
export async function apiError(res: Response, label: string): Promise<Error> {
  const body = import.meta.env.DEV ? ` ${(await res.text().catch(() => '')).slice(0, 300)}` : ''
  return new Error(`${label} failed: ${res.status}${body}`)
}
