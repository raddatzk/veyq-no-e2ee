import { API_BASE } from '../api/httpClient'

export type MediaUrlSource = {
  contentUrl: string
  thumbnailUrl?: string | null
  previewUrl?: string | null
  posterUrl?: string | null
  mimeType?: string | null
  imageWidth?: number | null
  imageHeight?: number | null
}

export function mediaContentUrl(media: MediaUrlSource): string {
  return absoluteMediaUrl(media.contentUrl)
}

export function mediaPreviewUrl(media: MediaUrlSource): string {
  return absoluteMediaUrl(media.thumbnailUrl || media.previewUrl || media.contentUrl)
}

export function mediaPreviewOnlyUrl(media: MediaUrlSource): string | null {
  return media.previewUrl ? absoluteMediaUrl(media.previewUrl) : null
}

export function mediaThumbnailUrl(media: MediaUrlSource): string | null {
  return media.thumbnailUrl ? absoluteMediaUrl(media.thumbnailUrl) : null
}

export function mediaPosterUrl(media: MediaUrlSource): string | null {
  const url = media.posterUrl || media.previewUrl || media.thumbnailUrl
  return url ? absoluteMediaUrl(url) : null
}

export function mediaImageDisplayUrl(media: MediaUrlSource): string {
  if (!media.mimeType?.startsWith('image/')) return mediaContentUrl(media)
  return media.previewUrl ? absoluteMediaUrl(media.previewUrl) : mediaContentUrl(media)
}

export function mediaContentOrPreviewUrl(media: MediaUrlSource): string {
  return absoluteMediaUrl(media.contentUrl || media.previewUrl || '')
}

export function absoluteMediaUrl(path: string): string {
  return `${API_BASE}${path}`
}
