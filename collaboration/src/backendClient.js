const DEFAULT_BACKEND_URL = 'http://veyq:8080'

export class BackendClient {
  constructor({
    baseUrl = process.env.VEYQ_BACKEND_URL || DEFAULT_BACKEND_URL,
    sidecarSecret = process.env.JOURNAL_LIVE_SIDECAR_SECRET || ''
  } = {}) {
    this.baseUrl = baseUrl.replace(/\/$/, '')
    this.sidecarSecret = sidecarSecret
  }

  async validateSession({ token, documentId }) {
    return this.#json('/api/collaboration/journal/session/validate', {
      method: 'POST',
      body: { token, documentId }
    })
  }

  async persistSnapshot({ token, documentId, markdown }) {
    return this.#json('/api/collaboration/journal/session/snapshot', {
      method: 'PUT',
      body: { token, documentId, markdown }
    })
  }

  async #json(path, { method, body }) {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(this.sidecarSecret ? { 'X-Veyq-Collaboration-Secret': this.sidecarSecret } : {})
      },
      body: JSON.stringify(body)
    })
    if (!response.ok) {
      const text = await response.text()
      throw new Error(`Backend request failed: ${response.status} ${text}`)
    }
    return response.json()
  }
}
