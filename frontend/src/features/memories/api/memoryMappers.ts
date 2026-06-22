import type {
  MemoryPlaylistSummary,
  MemoryTrackSummary,
  PublicShareMemoryHeaderSummary,
  PublicShareMediaSummary,
  PublicShareMemorySummary
} from '../../../api/generated'
import type { Memory, MemoryHeader, MemoryMedia, MemoryPlaylist, MemoryTrack } from './types'

export function normalizeMemory(
  memory: {
    parentId?: string
    coverMediaId?: string
    coverUrl?: string
    coverCrop?: Memory['coverCrop']
    scope?: Memory['scope']
    favorite?: boolean
  } & Omit<Memory, 'parentId' | 'coverMediaId' | 'coverUrl' | 'coverCrop' | 'scope' | 'favorite'>
): Memory {
  return {
    ...memory,
    parentId: memory.parentId ?? null,
    coverMediaId: memory.coverMediaId ?? null,
    coverUrl: memory.coverUrl ?? null,
    coverCrop: memory.coverCrop ?? null,
    favorite: memory.favorite ?? false,
    scope: memory.scope ?? null
  }
}

export function normalizeMemoryHeader(
  header: {
    coverUrl?: string
    coverCrop?: MemoryHeader['coverCrop']
    scope?: MemoryHeader['scope']
    favorite?: boolean
    participants?: MemoryHeader['participants']
  } & Omit<MemoryHeader, 'coverUrl' | 'coverCrop' | 'scope' | 'favorite' | 'participants'>
): MemoryHeader {
  return {
    ...header,
    coverUrl: header.coverUrl ?? null,
    coverCrop: header.coverCrop ?? null,
    favorite: header.favorite ?? false,
    scope: header.scope ?? null,
    participants: header.participants ?? []
  }
}

export function normalizeMemoryMedia(
  media: {
    capturedAt?: string
    thumbnailUrl?: string
    previewUrl?: string
    posterUrl?: string
    cameraMake?: string
    cameraModel?: string
    lensModel?: string
    imageWidth?: number
    imageHeight?: number
    gpsLatitude?: number
    gpsLongitude?: number
  } & Omit<
    MemoryMedia,
    | 'capturedAt'
    | 'thumbnailUrl'
    | 'previewUrl'
    | 'posterUrl'
    | 'cameraMake'
    | 'cameraModel'
    | 'lensModel'
    | 'imageWidth'
    | 'imageHeight'
    | 'gpsLatitude'
    | 'gpsLongitude'
  >
): MemoryMedia {
  return {
    ...media,
    capturedAt: media.capturedAt ?? null,
    thumbnailUrl: media.thumbnailUrl ?? null,
    previewUrl: media.previewUrl ?? null,
    posterUrl: media.posterUrl ?? null,
    cameraMake: media.cameraMake ?? null,
    cameraModel: media.cameraModel ?? null,
    lensModel: media.lensModel ?? null,
    imageWidth: media.imageWidth ?? null,
    imageHeight: media.imageHeight ?? null,
    gpsLatitude: media.gpsLatitude ?? null,
    gpsLongitude: media.gpsLongitude ?? null
  }
}

export function normalizeMemoryTrack(track: MemoryTrackSummary): MemoryTrack {
  return track
}

export function normalizeMemoryPlaylist(playlist: MemoryPlaylistSummary): MemoryPlaylist {
  return {
    ...playlist,
    note: playlist.note ?? undefined,
    featured: playlist.featured ?? false
  }
}

export function normalizePublicShareMemory(memory: PublicShareMemorySummary): Memory {
  return normalizeMemory({
    ...memory,
    parentId: memory.parentId || undefined,
    coverMediaId: memory.coverMediaId,
    coverUrl: memory.coverUrl,
    coverCrop: memory.coverCrop,
    favorite: false,
    scope: undefined
  })
}

export function normalizePublicShareMemoryHeader(header: PublicShareMemoryHeaderSummary): MemoryHeader {
  return normalizeMemoryHeader({
    ...header,
    coverUrl: header.coverUrl,
    coverCrop: header.coverCrop,
    favorite: false,
    scope: null
  })
}

export function normalizePublicShareMedia(media: PublicShareMediaSummary): MemoryMedia {
  return normalizeMemoryMedia({
    ...media,
    capturedAt: media.capturedAt ?? undefined,
    thumbnailUrl: media.thumbnailUrl ?? undefined,
    previewUrl: media.previewUrl ?? undefined,
    posterUrl: media.posterUrl ?? undefined,
    cameraMake: undefined,
    cameraModel: undefined,
    lensModel: undefined,
    imageWidth: undefined,
    imageHeight: undefined,
    gpsLatitude: media.gpsLatitude ?? undefined,
    gpsLongitude: media.gpsLongitude ?? undefined
  })
}
