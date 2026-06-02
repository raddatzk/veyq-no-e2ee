export async function mediaSetHash(mediaIds: string[]): Promise<string> {
  const input = [...mediaIds]
    .sort()
    .map((mediaId) => `${mediaId.length}:${mediaId}\n`)
    .join('')
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(input))
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}
