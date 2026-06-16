// Turn a picked File into a NewMedia: the original bytes + two client-generated downscales (canvas):
//  - thumbnail (~400px) → the gallery grid
//  - preview   (~2048px) → the lightbox / detail view
// The full-resolution original is uploaded too but only fetched on an explicit download — so the gallery and
// lightbox never pull full-res bytes, keeping bandwidth down and decoded-plaintext memory bounded. Videos
// upload without derivatives for now.
import type { NewMedia } from './media/media'
import { readExifGps } from './exifGps'

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('image decode failed'))
    img.src = url
  })
}

/** Downscale an image to fit `maxDim` (longest edge) as JPEG at `quality`; undefined for non-images. */
async function downscale(bytes: Uint8Array, mimeType: string, maxDim: number, quality: number): Promise<Uint8Array | undefined> {
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
    const blob = await new Promise<Blob | null>((res) => canvas.toBlob((b) => res(b), 'image/jpeg', quality))
    if (!blob) return undefined
    return new Uint8Array(await blob.arrayBuffer())
  } finally {
    URL.revokeObjectURL(url)
  }
}

/** Read a picked file into bytes + thumbnail + preview, ready for uploadMedia. Pulls GPS + capture time
 *  from EXIF when present (sealed client-side as sealedMeta) — the server never sees either. */
export async function fileToNewMedia(file: File): Promise<NewMedia> {
  const bytes = new Uint8Array(await file.arrayBuffer())
  const mimeType = file.type || 'application/octet-stream'
  const exif = mimeType === 'image/jpeg' ? readExifGps(bytes) : {}
  return {
    bytes,
    thumb: await downscale(bytes, mimeType, 400, 0.8),
    preview: await downscale(bytes, mimeType, 2048, 0.85),
    takenAt: exif.takenAt ?? new Date(file.lastModified || Date.now()).toISOString(),
    mimeType,
    lat: exif.lat,
    lng: exif.lng,
  }
}
