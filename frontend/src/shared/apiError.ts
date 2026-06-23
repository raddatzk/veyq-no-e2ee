/**
 * Build a UI-safe Error from a failed fetch Response: a stable "<label> failed: <status>" message. Client
 * error text surfaces in the UI and diagnostics, so never append server response bodies here, not even in dev.
 * Throw the result:
 *   `if (!res.ok) throw await apiError(res, 'list tracks')`
 */
export async function apiError(res: Response, label: string): Promise<Error> {
  return new Error(`${label} failed: ${res.status}`)
}
