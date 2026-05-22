import type { Page, Route } from '@playwright/test'
import { Buffer } from 'node:buffer'

export const adminUser = {
  userId: 1,
  email: 'admin@example.test',
  displayName: 'Administrator',
  serverRole: 'ServerOwner',
  passkeyCount: 0
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

export const rootNavigation = {
  location: { kind: 'TENANT', path: '/', displayName: 'Friends' },
  breadcrumbs: [{ label: 'Friends', path: '/' }],
  scopeRoots: [personalScopeRoot, groupScopeRoot],
  folders: []
}

export const personalNavigation = {
  location: { kind: 'USER', path: '/~administrator', displayName: 'Administrator' },
  breadcrumbs: [
    { label: 'Friends', path: '/' },
    { label: 'Persönlich', path: '/~administrator' }
  ],
  scopeRoots: [personalScopeRoot, groupScopeRoot],
  folders: []
}

export const groupNavigation = {
  location: { kind: 'GROUP', path: '/@travel', displayName: 'Travel' },
  breadcrumbs: [
    { label: 'Friends', path: '/' },
    { label: '@Travel', path: '/@travel' }
  ],
  scopeRoots: [personalScopeRoot, groupScopeRoot],
  folders: []
}

export const folderNavigation = {
  location: { kind: 'FOLDER', path: '/reisen', displayName: 'Reisen' },
  breadcrumbs: [
    { label: 'Friends', path: '/' },
    { label: 'Reisen', path: '/reisen' }
  ],
  scopeRoots: [personalScopeRoot, groupScopeRoot],
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
  mediaCount: 0,
  childCount: 1,
  scope: { type: 'TENANT' }
}

export const childMemory = {
  id: 'memory-child',
  name: 'abendessen',
  displayName: 'Abendessen',
  path: '/reisen/hochzeit-lisa-kevin/abendessen',
  parentId: weddingMemory.id,
  coverMediaId: null,
  coverUrl: null,
  mediaCount: 0,
  childCount: 0,
  scope: { type: 'TENANT' }
}

type AuthenticatedOwnerApiOptions = {
  folderMemories?: unknown[]
  onCreateFolder?: (body: unknown) => void
  onCreateMemory?: (body: unknown) => void
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

    if (method === 'GET' && url.pathname === '/api/friends/memory-navigation') {
      const path = url.searchParams.get('path') || '/'
      await fulfillJson(route, navigationForPath(path))
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
        groups: [{ name: groupScopeRoot.name, displayName: groupScopeRoot.displayName }]
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

export async function mockMemoryDetailApi(page: Page) {
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

    if (method === 'GET' && url.pathname === '/api/friends/memory-navigation') {
      const path = url.searchParams.get('path') || '/'
      await fulfillJson(route, navigationForPath(path))
      return
    }

    if (method === 'GET' && url.pathname === '/api/friends/memories/by-path') {
      const path = url.searchParams.get('path')
      await fulfillJson(route, path === weddingMemory.path ? weddingMemory : childMemory)
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${weddingMemory.id}/children`) {
      await fulfillJson(route, [childMemory])
      return
    }

    if (method === 'GET' && url.pathname === `/api/friends/memories/${weddingMemory.id}/media`) {
      await fulfillJson(route, [])
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

export function navigationForPath(path: string) {
  if (path === personalScopeRoot.path) return personalNavigation
  if (path === groupScopeRoot.path) return groupNavigation
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
