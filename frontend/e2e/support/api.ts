import type { Page, Route } from '@playwright/test'
import { Buffer } from 'node:buffer'

export const adminUser = {
  userId: 1,
  email: 'admin@example.test',
  displayName: 'Administrator',
  permalink: 'administrator',
  serverRole: 'ServerOwner',
  passkeyCount: 0
}

export const longNameUser = {
  userId: 2,
  email: 'alexandra.maximiliana.really-long-email-address@example.test',
  displayName: 'Alexandra Maximiliana von Sehrlangem Anzeigenamen',
  permalink: 'alexandra-maximiliana',
  serverRole: 'User',
  passkeyCount: 2
}

export const tenant = {
  id: 1,
  name: 'friends',
  displayName: 'Friends',
  domain: '127.0.0.1:8081',
  role: 'TenantOwner',
  admins: [adminUser]
}

export const personalScopeRoot = {
  kind: 'USER',
  name: 'administrator',
  displayName: 'Administrator',
  path: '/~administrator'
}

export const groupScopeRoot = {
  kind: 'GROUP',
  name: 'travel',
  displayName: 'Travel',
  path: '/@travel'
}

export const overflowGroupScopeRoots = [
  { kind: 'GROUP', name: 'family', displayName: 'Family', path: '/@family' },
  { kind: 'GROUP', name: 'school', displayName: 'School Friends', path: '/@school' },
  { kind: 'GROUP', name: 'long-group', displayName: 'Group With A Very Long Name', path: '/@long-group' },
  { kind: 'GROUP', name: 'neighbors', displayName: 'Neighbors', path: '/@neighbors' }
]

export const rootNavigation = {
  location: { kind: 'TENANT', path: '/', displayName: 'Friends' },
  breadcrumbs: [{ label: 'Friends', path: '/' }],
  scopeRoots: [personalScopeRoot, groupScopeRoot],
  favoriteMemories: [],
  folders: []
}

export const personalNavigation = {
  location: { kind: 'USER', path: '/~administrator', displayName: 'Administrator' },
  breadcrumbs: [
    { label: 'Friends', path: '/' },
    { label: 'Persönlich', path: '/~administrator' }
  ],
  scopeRoots: [personalScopeRoot, groupScopeRoot],
  favoriteMemories: [],
  folders: []
}

export const groupNavigation = {
  location: { kind: 'GROUP', path: '/@travel', displayName: 'Travel' },
  breadcrumbs: [
    { label: 'Friends', path: '/' },
    { label: '@Travel', path: '/@travel' }
  ],
  scopeRoots: [personalScopeRoot, groupScopeRoot],
  favoriteMemories: [],
  folders: []
}

export const folderNavigation = {
  location: { kind: 'FOLDER', path: '/reisen', displayName: 'Reisen' },
  breadcrumbs: [
    { label: 'Friends', path: '/' },
    { label: 'Reisen', path: '/reisen' }
  ],
  scopeRoots: [personalScopeRoot, groupScopeRoot],
  favoriteMemories: [],
  folders: []
}

export const weddingMemory = {
  id: 'memory-wedding',
  name: 'hochzeit-lisa-kevin',
  displayName: 'Hochzeit Lisa & Kevin',
  path: '/reisen/hochzeit-lisa-kevin',
  parentId: null,
  coverMediaId: null,
  coverUrl: '/api/friends/memories/memory-wedding/cover/content',
  startDate: '2026-05-03',
  endDate: '2026-05-04',
  mediaCount: 0,
  childCount: 1,
  scope: { type: 'TENANT' }
}

export const longNameMemory = {
  ...weddingMemory,
  id: 'memory-long-name',
  name: 'sommerreise-mit-einem-aussergewoehnlich-langen-memory-namen',
  displayName: 'Sommerreise mit einem aussergewoehnlich langen Memory Namen fuer Responsive Tests',
  path: '/reisen/sommerreise-mit-einem-aussergewoehnlich-langen-memory-namen',
  mediaCount: 7,
  childCount: 3
}

export const childMemory = {
  id: 'memory-child',
  name: 'abendessen',
  displayName: 'Abendessen',
  path: '/reisen/hochzeit-lisa-kevin/abendessen',
  parentId: weddingMemory.id,
  coverMediaId: null,
  coverUrl: null,
  startDate: '2026-05-03',
  endDate: '2026-05-03',
  mediaCount: 0,
  childCount: 0,
  scope: { type: 'TENANT' }
}

export const publicShareRootMemory = {
  id: weddingMemory.id,
  name: weddingMemory.name,
  displayName: weddingMemory.displayName,
  path: '/',
  parentId: null,
  coverMediaId: null,
  coverUrl: null,
  coverCrop: null,
  startDate: weddingMemory.startDate,
  endDate: weddingMemory.endDate,
  mediaCount: 0,
  childCount: 1
}

type AuthenticatedOwnerApiOptions = {
  folderMemories?: unknown[]
  favoriteMemories?: unknown[]
  scopeRoots?: unknown[]
  onCreateFolder?: (body: unknown) => void
  onCreateMemory?: (body: unknown) => void
}

type MemoryDetailApiOptions = {
  shares?: unknown[]
  memory?: typeof weddingMemory
  childMemories?: unknown[]
  media?: unknown[]
  tracks?: unknown[]
  playlists?: unknown[]
  journalMarkdown?: string
  onCreateShare?: (body: unknown) => void
  onUpdateShare?: (body: unknown) => void
}

export const geotaggedMedia = {
  id: 'media-map-e2e',
  memoryId: weddingMemory.id,
  title: 'Terrace',
  filename: 'terrace.jpg',
  uploader: { type: 'USER', displayName: 'Kevin' },
  createdAt: '2026-05-24T16:30:00Z',
  capturedAt: '2026-05-03T16:30:00Z',
  capturedAtOverride: false,
  mimeType: 'image/jpeg',
  sizeBytes: 12,
  contentUrl: '/api/public-shares/shr-e2e/memories/memory-wedding/media/media-map-e2e/content',
  previewUrl: '/api/public-shares/shr-e2e/memories/memory-wedding/media/media-map-e2e/preview',
  cameraMake: null,
  cameraModel: null,
  lensModel: null,
  imageWidth: 1200,
  imageHeight: 800,
  gpsLatitude: 52.52,
  gpsLongitude: 13.405
}

export const publicTrack = {
  id: 'track-e2e',
  memoryId: weddingMemory.id,
  title: 'Morning Walk',
  activityType: 'WALKING',
  distanceMeters: 2400,
  ascentMeters: 120,
  descentMeters: 80,
  pointCount: 3,
  startedAt: '2026-05-03T08:00:00Z',
  endedAt: '2026-05-03T09:00:00Z',
  bounds: {
    minLatitude: 52.51,
    minLongitude: 13.39,
    maxLatitude: 52.53,
    maxLongitude: 13.42
  },
  geometry: {
    type: 'LineString',
    coordinates: [
      [13.39, 52.51],
      [13.405, 52.52],
      [13.42, 52.53]
    ]
  },
  contentUrl: '/api/public-shares/shr-e2e/memories/memory-wedding/tracks/track-e2e/content',
  createdAt: '2026-05-24T10:00:00Z',
  updatedAt: '2026-05-24T10:00:00Z'
}

export const publicPlaylist = {
  id: 'playlist-e2e',
  memoryId: weddingMemory.id,
  title: 'Roadtrip Soundtrack',
  note: 'Songs from the weekend',
  featured: true,
  provider: 'YOUTUBE',
  url: 'https://www.youtube.com/playlist?list=PL1234567890abcdef',
  externalId: 'PL1234567890abcdef',
  createdAt: '2026-05-24T10:00:00Z',
  updatedAt: '2026-05-24T10:00:00Z'
}

export const serverSettings = {
  displayName: 'veyq Testserver',
  userManagementAvailable: true,
  license: {
    edition: 'COMMUNITY',
    license: 'AGPL-3.0-only',
    sourceUrl: 'https://github.com/veyq/veyq',
    noticeUrl: 'https://github.com/veyq/veyq/blob/main/NOTICE',
    trademarkPolicyUrl: 'https://github.com/veyq/veyq/blob/main/docs/legal/trademark-policy.md',
    commercialBoundaryUrl: 'https://github.com/veyq/veyq/blob/main/docs/legal/commercial-boundary.md',
    version: 'e2e'
  }
}

export function fulfillJson(route: Route, json: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(json)
  })
}

export async function mockInitialSetupApi(page: Page) {
  let tenantCreated = false

  await page.route('**://*/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'GET' && url.pathname === '/api/frontend/bootstrap') {
      await fulfillJson(route, { error: 'Setup required' }, 503)
      return
    }

    if (method === 'POST' && url.pathname === '/api/setup/admin') {
      await fulfillJson(route, adminUser)
      return
    }

    if (method === 'POST' && url.pathname === '/api/auth/login') {
      await fulfillJson(route, adminUser)
      return
    }

    if (method === 'GET' && url.pathname === '/api/tenants') {
      await fulfillJson(route, { tenants: tenantCreated ? [tenant] : [] })
      return
    }

    if (method === 'POST' && url.pathname === '/api/tenants') {
      tenantCreated = true
      await fulfillJson(route, tenant)
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/memory-navigation') {
      await fulfillJson(route, rootNavigation)
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/memories/by-folder') {
      await fulfillJson(route, [])
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

export async function mockAuthenticatedOwnerApi(
  page: Page,
  options: AuthenticatedOwnerApiOptions = {}
) {
  await page.route('**://*/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'GET' && url.pathname === '/api/frontend/bootstrap') {
      await fulfillJson(route, {
        serverId: 'e2e',
        user: adminUser,
        tenants: [tenant]
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/settings/server') {
      await fulfillJson(route, serverSettings)
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/memory-navigation') {
      const path = url.searchParams.get('path') || '/'
      const navigation = navigationForPath(path)
      await fulfillJson(route, {
        ...navigation,
        favoriteMemories: options.favoriteMemories ?? navigation.favoriteMemories,
        scopeRoots: options.scopeRoots ?? navigation.scopeRoots
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/memories/by-folder') {
      await fulfillJson(route, options.folderMemories ?? [])
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/memories/memory-wedding/cover/content') {
      await fulfillPng(route)
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/groups') {
      await fulfillJson(route, {
        groups: (options.scopeRoots ?? [groupScopeRoot])
          .filter((scopeRoot) => isRecord(scopeRoot) && scopeRoot.kind === 'GROUP')
          .map((scopeRoot) => ({
            name: String(scopeRoot.name),
            displayName: String(scopeRoot.displayName)
          }))
      })
      return
    }

    if (method === 'POST' && url.pathname === '/api/friends/folders') {
      const body = request.postDataJSON()
      options.onCreateFolder?.(body)
      await fulfillJson(route, {
        id: 'folder-created',
        name: body.name,
        displayName: body.displayName,
        path: `/${body.name}`
      })
      return
    }

    if (method === 'POST' && url.pathname === '/api/friends/memories') {
      const body = request.postDataJSON()
      options.onCreateMemory?.(body)
      await fulfillJson(route, {
        id: 'memory-created',
        name: body.name,
        displayName: body.displayName,
        path: `${body.folderPath === '/' ? '' : body.folderPath}/${body.name}`,
        parentId: body.parentId ?? null,
        coverMediaId: null,
        coverUrl: null,
        startDate: body.startDate,
        endDate: body.endDate,
        mediaCount: 0,
        childCount: 0,
        scope: body.scope ?? { type: 'TENANT' }
      })
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

export async function mockAuthenticatedOwnerWithoutTenants(page: Page) {
  await page.route('**://*/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'GET' && url.pathname === '/api/frontend/bootstrap') {
      await fulfillJson(route, {
        serverId: 'e2e',
        user: adminUser,
        tenants: []
      })
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

export async function mockMemoryDetailApi(page: Page, options: MemoryDetailApiOptions = {}) {
  const memory = options.memory ?? weddingMemory
  await page.route('**://*/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'GET' && url.pathname === '/api/frontend/bootstrap') {
      await fulfillJson(route, {
        serverId: 'e2e',
        user: adminUser,
        tenants: [tenant]
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/settings/server') {
      await fulfillJson(route, serverSettings)
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/memory-navigation') {
      const path = url.searchParams.get('path') || '/'
      await fulfillJson(route, navigationForPath(path))
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/memories/by-path') {
      const path = url.searchParams.get('path')
      if (path === memory.path) {
        await fulfillJson(route, memory)
        return
      }
      if (path === childMemory.path) {
        await fulfillJson(route, childMemory)
        return
      }
      await fulfillJson(route, { error: 'Memory not found' }, 404)
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${memory.id}/header`) {
      await fulfillJson(route, {
        id: memory.id,
        displayName: memory.displayName,
        path: memory.path,
        coverUrl: memory.coverUrl,
        coverCrop: null,
        startDate: memory.startDate,
        endDate: memory.endDate,
        directMediaCount: memory.mediaCount,
        descendantMediaCount: 0,
        childMemoryCount: memory.childCount,
        scope: memory.scope,
        favorite: false,
        participants: []
      })
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${memory.id}/journal`) {
      await fulfillJson(route, {
        memoryId: memory.id,
        markdown: options.journalMarkdown ?? '',
        updatedAt: '2026-05-24T10:00:00Z'
      })
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${memory.id}/children`) {
      await fulfillJson(route, options.childMemories ?? [childMemory])
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${memory.id}/media`) {
      await fulfillJson(route, options.media ?? [])
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${memory.id}/tracks`) {
      await fulfillJson(route, options.tracks ?? [])
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${memory.id}/playlists`) {
      await fulfillJson(route, options.playlists ?? [])
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${memory.id}/shares`) {
      await fulfillJson(route, options.shares ?? [])
      return
    }

    if (method === 'POST' && url.pathname === `/api/friends/memories/${memory.id}/shares`) {
      const body = request.postDataJSON()
      options.onCreateShare?.(body)
      await fulfillJson(route, {
        id: 'shr-e2e',
        memoryId: memory.id,
        name: body.name ?? null,
        role: body.role,
        enabled: true,
        shareUrl: '/s/shr-e2e',
        hasPassword: Boolean(body.password),
        createdAt: '2026-05-24T10:00:00Z',
        updatedAt: '2026-05-24T10:00:00Z'
      })
      return
    }

    if (method === 'PUT' && url.pathname === '/api/friends/memory-shares/shr-e2e') {
      const body = request.postDataJSON()
      options.onUpdateShare?.(body)
      await fulfillJson(route, {
        id: 'shr-e2e',
        memoryId: weddingMemory.id,
        name: 'Familie',
        role: body.role ?? 'VIEWER',
        enabled: body.enabled ?? true,
        shareUrl: '/s/shr-e2e',
        hasPassword: body.password !== '',
        createdAt: '2026-05-24T10:00:00Z',
        updatedAt: '2026-05-24T10:00:00Z'
      })
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

export async function mockResponsiveSettingsApi(page: Page) {
  await page.route('**://*/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'GET' && url.pathname === '/api/frontend/bootstrap') {
      await fulfillJson(route, {
        serverId: 'e2e',
        user: adminUser,
        tenants: [tenant]
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/settings') {
      const path = url.searchParams.get('path') || '/settings'
      await fulfillJson(route, settingsResponseForPath(path))
      return
    }

    if (method === 'GET' && url.pathname === '/api/settings/server') {
      await fulfillJson(route, {
        ...serverSettings,
        displayName: 'veyq Testserver mit langem Namen fuer Responsive Checks'
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/users/me') {
      await fulfillJson(route, {
        ...adminUser,
        pendingEmail: 'administrator.pending.with-a-long-local-part@example.test'
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/users/me/passkeys') {
      await fulfillJson(route, {
        passkeys: [
          {
            passkeyId: 1,
            displayName: 'MacBook Pro Passkey mit sehr langem Geraetenamen',
            createdAt: '2026-05-24T10:00:00Z',
            lastUsedAt: '2026-05-25T10:00:00Z'
          },
          {
            passkeyId: 2,
            displayName: 'Backup Security Key',
            createdAt: '2026-05-20T10:00:00Z',
            lastUsedAt: null
          }
        ]
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/tenants/friends/users') {
      await fulfillJson(route, {
        users: [
          {
            user: adminUser,
            email: adminUser.email,
            role: 'TenantOwner',
            status: 'Active'
          },
          {
            user: longNameUser,
            email: longNameUser.email,
            role: 'TenantMember',
            status: 'Pending'
          }
        ]
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/groups') {
      await fulfillJson(route, {
        groups: [
          { id: 1, name: groupScopeRoot.name, displayName: groupScopeRoot.displayName },
          {
            id: 2,
            name: 'familienurlaub-mit-einem-sehr-langen-gruppennamen',
            displayName: 'Familienurlaub mit einem sehr langen Gruppennamen'
          }
        ]
      })
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

export async function mockPublicViewerShareApi(
  page: Page,
  options: {
    media?: unknown[]
    tracks?: unknown[]
    playlists?: unknown[]
    journalMarkdown?: string
  } = {}
) {
  await page.route('**://*/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'GET' && url.pathname === '/api/frontend/bootstrap') {
      await fulfillJson(route, {
        serverId: 'e2e',
        user: null,
        tenants: []
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/public-shares/shr-e2e') {
      await fulfillJson(route, {
        id: 'shr-e2e',
        role: 'VIEWER',
        authenticated: false,
        passwordRequired: false,
        displayNameRequired: false,
        canViewGallery: true,
        canUpload: false,
        canEdit: false,
        canDelete: false,
        rootMemory: {
          ...publicShareRootMemory,
          mediaCount: (options.media ?? []).length
        }
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/public-shares/shr-e2e/memories/by-path') {
      await fulfillJson(route, {
        ...publicShareRootMemory,
        mediaCount: (options.media ?? []).length
      })
      return
    }

    if (method === 'GET' && url.pathname === `/api/public-shares/shr-e2e/memories/${weddingMemory.id}/header`) {
      await fulfillJson(route, {
        id: publicShareRootMemory.id,
        displayName: publicShareRootMemory.displayName,
        path: publicShareRootMemory.path,
        coverUrl: publicShareRootMemory.coverUrl,
        coverCrop: publicShareRootMemory.coverCrop,
        startDate: publicShareRootMemory.startDate,
        endDate: publicShareRootMemory.endDate,
        directMediaCount: (options.media ?? []).length,
        descendantMediaCount: 0,
        childMemoryCount: publicShareRootMemory.childCount,
        scope: null,
        favorite: false,
        participants: []
      })
      return
    }

    if (method === 'GET' && url.pathname === `/api/public-shares/shr-e2e/memories/${weddingMemory.id}/children`) {
      await fulfillJson(route, [
        {
          ...childMemory,
          path: '/abendessen',
          scope: undefined,
          coverCrop: null
        }
      ])
      return
    }

    if (method === 'GET' && url.pathname === `/api/public-shares/shr-e2e/memories/${weddingMemory.id}/media`) {
      await fulfillJson(route, options.media ?? [])
      return
    }

    if (method === 'GET' && url.pathname === `/api/public-shares/shr-e2e/memories/${weddingMemory.id}/journal`) {
      await fulfillJson(route, {
        memoryId: weddingMemory.id,
        markdown: options.journalMarkdown ?? '',
        updatedAt: '2026-05-24T10:00:00Z'
      })
      return
    }

    if (method === 'GET' && url.pathname === `/api/public-shares/shr-e2e/memories/${weddingMemory.id}/tracks`) {
      await fulfillJson(route, options.tracks ?? [])
      return
    }

    if (method === 'GET' && url.pathname === `/api/public-shares/shr-e2e/memories/${weddingMemory.id}/playlists`) {
      await fulfillJson(route, options.playlists ?? [])
      return
    }

    if (
      method === 'GET' &&
      url.pathname === `/api/public-shares/shr-e2e/memories/${weddingMemory.id}/media/media-map-e2e/preview`
    ) {
      await fulfillPng(route)
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

export async function mockPublicUploaderShareApi(
  page: Page,
  options: {
    onCreateUpload?: (body: unknown) => void
    onChunkUpload?: (chunkIndex: string) => void
  } = {}
) {
  await page.route('**://*/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'GET' && url.pathname === '/api/frontend/bootstrap') {
      await fulfillJson(route, {
        serverId: 'e2e',
        user: null,
        tenants: []
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/public-shares/shr-upload') {
      await fulfillJson(route, {
        id: 'shr-upload',
        role: 'UPLOADER',
        authenticated: true,
        passwordRequired: false,
        displayNameRequired: false,
        canViewGallery: false,
        canUpload: true,
        canEdit: false,
        canDelete: false,
        rootMemory: {
          ...publicShareRootMemory,
          displayName: 'Sommerreise Upload'
        }
      })
      return
    }

    if (method === 'POST' && url.pathname === '/api/public-shares/shr-upload/media/uploads') {
      options.onCreateUpload?.(request.postDataJSON())
      await fulfillJson(route, {
        uploadId: 'upl-public-e2e',
        chunkSizeBytes: 1024
      })
      return
    }

    if (method === 'GET' && url.pathname === '/api/public-shares/shr-upload/media/uploads/upl-public-e2e') {
      await fulfillJson(route, {
        uploadId: 'upl-public-e2e',
        memoryId: publicShareRootMemory.id,
        filename: 'beach.jpg',
        mimeType: 'image/jpeg',
        sizeBytes: 10,
        title: 'beach.jpg',
        chunkSizeBytes: 1024,
        uploadedChunks: [],
        uploadedBytes: 0,
        createdAt: '2026-05-24T10:00:00Z',
        updatedAt: '2026-05-24T10:00:00Z'
      })
      return
    }

    const chunkMatch = url.pathname.match(
      /^\/api\/public-shares\/shr-upload\/media\/uploads\/upl-public-e2e\/chunks\/([^/]+)$/
    )
    if (method === 'PUT' && chunkMatch) {
      options.onChunkUpload?.(chunkMatch[1])
      await route.fulfill({ status: 204 })
      return
    }

    if (method === 'POST' && url.pathname === '/api/public-shares/shr-upload/media/uploads/upl-public-e2e/complete') {
      await fulfillJson(route, {
        id: 'med-public-e2e',
        memoryId: publicShareRootMemory.id,
        title: 'beach.jpg',
        filename: 'beach.jpg',
        mimeType: 'image/jpeg',
        sizeBytes: 10,
        capturedAt: null,
        createdAt: '2026-05-24T10:00:00Z',
        updatedAt: '2026-05-24T10:00:00Z',
        uploader: { type: 'SHARE_GUEST', displayName: 'Gast' },
        contentUrl: '/api/public-shares/shr-upload/media/med-public-e2e/content',
        previewUrl: '/api/public-shares/shr-upload/media/med-public-e2e/preview'
      })
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

function settingsResponseForPath(path: string) {
  if (path === '/~me') {
    return {
      subject: { type: 'USER', label: adminUser.displayName },
      breadcrumbs: [
        { label: 'Einstellungen', path: '/~me' },
        { label: adminUser.displayName, path: '/~me' }
      ]
    }
  }

  if (path === '/settings') {
    return {
      subject: { type: 'SERVER', label: 'Server' },
      breadcrumbs: [
        { label: 'Einstellungen', path: '/settings' },
        { label: 'Server', path: '/settings' }
      ]
    }
  }

  return {
    subject: { type: 'TENANT', label: tenant.displayName },
    breadcrumbs: [
      { label: 'Einstellungen', path: `/${tenant.name}/settings` },
      { label: tenant.displayName, path: `/${tenant.name}/settings` }
    ]
  }
}

export function navigationForPath(path: string) {
  if (
    path === personalScopeRoot.path ||
    path.startsWith(`${personalScopeRoot.path}/`) ||
    path === '/~me' ||
    path.startsWith('/~me/')
  ) {
    return personalNavigation
  }
  if (path === groupScopeRoot.path || path.startsWith(`${groupScopeRoot.path}/`)) {
    return groupNavigation
  }
  if (path === folderNavigation.location.path) return folderNavigation
  return rootNavigation
}

export function fulfillUnhandled(route: Route, method: string, pathname: string) {
  return fulfillJson(route, { error: `Unhandled E2E API route: ${method} ${pathname}` }, 404)
}

export function fulfillPng(route: Route) {
  return route.fulfill({
    status: 200,
    contentType: 'image/png',
    body: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=',
      'base64'
    )
  })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
