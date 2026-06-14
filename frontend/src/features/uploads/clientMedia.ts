// Turn a picked File into a NewMedia: the original bytes + a small client-generated thumbnail (downscaled
// via canvas). The thumbnail is what the gallery grid loads, so generating it here keeps the server blind
// to full-resolution bytes until a lightbox actually needs them. Videos upload without a thumbnail for now.
import type { NewMedia } from './media/media'

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('image decode failed'))
    img.src = url
  })
}

async function makeThumbnail(bytes: Uint8Array, mimeType: string, maxDim = 400): Promise<Uint8Array | undefined> {
  if (!mimeType.startsWith('image/')) return undefined
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: mimeType }))
  try {
    const img = await loadImage(url)
    const scale = Math.min(1, maxDim / Math.max(img.naturalWidth, img.naturalHeight))
    const w = Math.max(1, Math.round(img.naturalWidth * scale))
    const h = Math.max(1, Math.round(img.naturalHeight * scale))
    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    canvas.getContext('2d')!.drawImage(img, 0, 0, w, h)
    const blob = await new Promise<Blob | null>((res) => canvas.toBlob((b) => res(b), 'image/jpeg', 0.8))
    if (!blob) return undefined
    return new Uint8Array(await blob.arrayBuffer())
  } finally {
    URL.revokeObjectURL(url)
  }
}

/** Read a picked file into bytes + an optional thumbnail, ready for uploadMedia. */
export async function fileToNewMedia(file: File): Promise<NewMedia> {
  const bytes = new Uint8Array(await file.arrayBuffer())
  const mimeType = file.type || 'application/octet-stream'
  return {
    bytes,
    thumb: await makeThumbnail(bytes, mimeType),
    takenAt: new Date(file.lastModified || Date.now()).toISOString(),
    mimeType,
  }
}
