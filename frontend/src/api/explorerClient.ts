import { apiFetch, callApi } from './httpClient'

export type ExplorerContextType = 'Tenant' | 'Folder' | 'Album' | 'tenant' | 'folder' | 'album' | string
export type LibraryItemType = 'folder' | 'album'

export type ExplorerContext = {
  type: ExplorerContextType
  path: string
  displayName: string
}

export type ExplorerBreadcrumb = {
  type: ExplorerContextType
  name: string
  displayName: string
  path: string
}

export type ExplorerFolder = {
  id: string
  name: string
  displayName: string
  path: string
}

export type ExplorerAlbum = {
  id: string
  name: string
  displayName: string
  path: string
  coverMediaId: string | null
}

export type ExplorerMedia = {
  id: string
  title: string
  albumPath: string | null
  ownerUserId: number
  createdAt: string
  mimeType: string
  sizeBytes: number
  contentUrl: string
}

export type ExplorerResponse = {
  context: ExplorerContext
  breadcrumbs: ExplorerBreadcrumb[]
  folders: ExplorerFolder[]
  albums: ExplorerAlbum[]
}

export type LibraryShares = {
  users: UserShare[]
  groups: GroupShare[]
}

export type UserShare = {
  userId: number
  email: string
  displayName: string
}

export type GroupShare = {
  groupId: number
  name: string
  displayName: string
}

export type CreateLibraryItemRequest = {
  parentPath: string | null
  name: string
  displayName: string
}

export type UpdateLibraryItemRequest = {
  path: string
  name: string
  displayName: string
}

export type MoveLibraryItemRequest = {
  path: string
  newParentPath: string | null
  name: string | null
}

export type StreamMediaOptions = {
  signal?: AbortSignal
}

export type UploadMediaRequest = {
  albumPath: string
  file: File
  title?: string
  onProgress?: (uploadedBytes: number, totalBytes: number) => void
}

type MediaUploadSession = {
  uploadId: string
  chunkSizeBytes: number
}

function explorerPath(tenantName: string, suffix = ''): string {
  return `/api/${encodeURIComponent(tenantName)}/explorer${suffix}`
}

function libraryPath(tenantName: string, segment: string, suffix = ''): string {
  return `/api/${encodeURIComponent(tenantName)}/${segment}${suffix}`
}

function pathQuery(path: string): string {
  return `path=${encodeURIComponent(path || '/')}`
}

function itemSegment(type: LibraryItemType): string {
  return type === 'folder' ? 'folders' : 'albums'
}

export function getExplorer(tenantName: string, path: string): Promise<ExplorerResponse> {
  return callApi(explorerPath(tenantName, `?${pathQuery(path)}`))
}

export async function streamExplorerMedia(
  tenantName: string,
  path: string,
  onItem: (item: ExplorerMedia) => void,
  options: StreamMediaOptions = {}
): Promise<void> {
  const response = await apiFetch(libraryPath(tenantName, 'media', `?${pathQuery(path)}`), {
    signal: options.signal,
    headers: { Accept: 'application/x-ndjson' }
  })

  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: 'Medien konnten nicht geladen werden' }))
    throw new Error(body.error || 'Medien konnten nicht geladen werden')
  }

  if (!response.body) {
    const text = await response.text()
    parseNdjson(text, onItem)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    lines.forEach((line) => parseLine(line, onItem))
  }

  buffer += decoder.decode()
  parseLine(buffer, onItem)
}

export function createFolder(tenantName: string, request: CreateLibraryItemRequest): Promise<ExplorerFolder> {
  return callApi(libraryPath(tenantName, 'folders'), {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export function createAlbum(tenantName: string, request: CreateLibraryItemRequest): Promise<ExplorerAlbum> {
  return callApi(libraryPath(tenantName, 'albums'), {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export function updateLibraryItem(
  tenantName: string,
  type: LibraryItemType,
  request: UpdateLibraryItemRequest
): Promise<ExplorerFolder | ExplorerAlbum> {
  return callApi(libraryPath(tenantName, itemSegment(type), '/update'), {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export function moveLibraryItem(
  tenantName: string,
  type: LibraryItemType,
  request: MoveLibraryItemRequest
): Promise<ExplorerFolder | ExplorerAlbum> {
  return callApi(libraryPath(tenantName, itemSegment(type), '/move'), {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export function deleteLibraryItem(tenantName: string, type: LibraryItemType, path: string): Promise<void> {
  return callApi(libraryPath(tenantName, itemSegment(type), `?${pathQuery(path)}`), {
    method: 'DELETE'
  })
}

export function getLibraryShares(tenantName: string, type: LibraryItemType, path: string): Promise<LibraryShares> {
  return callApi(libraryPath(tenantName, itemSegment(type), `/shares?${pathQuery(path)}`))
}

export function shareLibraryItemWithUser(
  tenantName: string,
  type: LibraryItemType,
  path: string,
  userId: number
): Promise<LibraryShares> {
  return callApi(libraryPath(tenantName, itemSegment(type), `/shares/users?${pathQuery(path)}`), {
    method: 'POST',
    body: JSON.stringify({ userId })
  })
}

export function shareLibraryItemWithGroup(
  tenantName: string,
  type: LibraryItemType,
  path: string,
  groupId: number
): Promise<LibraryShares> {
  return callApi(libraryPath(tenantName, itemSegment(type), `/shares/groups?${pathQuery(path)}`), {
    method: 'POST',
    body: JSON.stringify({ groupId })
  })
}

export function unshareLibraryItemWithUser(
  tenantName: string,
  type: LibraryItemType,
  path: string,
  userId: number
): Promise<LibraryShares> {
  return callApi(libraryPath(tenantName, itemSegment(type), `/shares/users/${userId}?${pathQuery(path)}`), {
    method: 'DELETE'
  })
}

export function unshareLibraryItemWithGroup(
  tenantName: string,
  type: LibraryItemType,
  path: string,
  groupId: number
): Promise<LibraryShares> {
  return callApi(libraryPath(tenantName, itemSegment(type), `/shares/groups/${groupId}?${pathQuery(path)}`), {
    method: 'DELETE'
  })
}

export function setAlbumCover(
  tenantName: string,
  albumPath: string,
  mediaId: string,
  hidden: boolean
): Promise<ExplorerAlbum> {
  return callApi(libraryPath(tenantName, 'albums', '/cover'), {
    method: 'POST',
    body: JSON.stringify({ albumPath, mediaId, hidden })
  })
}

export async function uploadMedia(tenantName: string, request: UploadMediaRequest): Promise<ExplorerMedia> {
  const session = await callApi<MediaUploadSession>(libraryPath(tenantName, 'media', '/uploads'), {
    method: 'POST',
    body: JSON.stringify({
      albumPath: request.albumPath,
      filename: request.file.name,
      mimeType: request.file.type,
      sizeBytes: request.file.size,
      title: request.title
    })
  })

  const chunkSize = session.chunkSizeBytes
  let uploadedBytes = 0
  for (let start = 0, index = 0; start < request.file.size; start += chunkSize, index += 1) {
    const end = Math.min(start + chunkSize, request.file.size)
    const response = await apiFetch(libraryPath(
      tenantName,
      'media',
      `/uploads/${encodeURIComponent(session.uploadId)}/chunks/${index}`
    ), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: request.file.slice(start, end)
    })

    if (!response.ok) {
      const body = await response.json().catch(() => ({ error: 'Chunk konnte nicht hochgeladen werden' }))
      throw new Error(body.error || 'Chunk konnte nicht hochgeladen werden')
    }

    uploadedBytes = end
    request.onProgress?.(uploadedBytes, request.file.size)
  }

  return callApi(libraryPath(tenantName, 'media', `/uploads/${encodeURIComponent(session.uploadId)}/complete`), {
    method: 'POST',
  })
}

function parseNdjson(text: string, onItem: (item: ExplorerMedia) => void) {
  text.split('\n').forEach((line) => parseLine(line, onItem))
}

function parseLine(line: string, onItem: (item: ExplorerMedia) => void) {
  const trimmed = line.trim()
  if (!trimmed) return
  onItem(JSON.parse(trimmed) as ExplorerMedia)
}
