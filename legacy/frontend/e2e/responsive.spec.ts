import { expect, test, type Page } from '@playwright/test'
import {
  adminUser,
  geotaggedMedia,
  groupScopeRoot,
  longNameMemory,
  mockAuthenticatedOwnerApi,
  mockInitialSetupApi,
  mockMemoryDetailApi,
  mockPublicViewerShareApi,
  publicPlaylist,
  publicTrack,
  mockResponsiveSettingsApi,
  overflowGroupScopeRoots,
  personalScopeRoot,
  weddingMemory,
  fulfillJson,
  fulfillUnhandled
} from './support/api'

const viewports = [
  { name: 'mobile', width: 375, height: 667 },
  { name: 'mobile-wide', width: 430, height: 932 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'sidebar-breakpoint', width: 1020, height: 477 },
  { name: 'sidebar-breakpoint-tall', width: 1020, height: 900 },
  { name: 'small-desktop', width: 1024, height: 768 },
  { name: 'desktop', width: 1440, height: 900 }
]

for (const viewport of viewports) {
  test.describe(`responsive layout at ${viewport.name}`, () => {
    test.use({ viewport })

    test('public auth screens stay within the viewport', async ({ page }) => {
      await mockAnonymousSetupCompletedApi(page)

      await page.goto('/login')
      await expect(page.getByRole('button', { name: 'Mit Passkey einloggen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.login-card')

      await page.goto('/password/reset/1#reset-token')
      await expect(page.getByRole('button', { name: 'Passwort setzen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.login-card')

      await page.goto('/invite/1#invite-token')
      await expect(page.getByRole('button', { name: 'Account mit Passkey erstellen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.login-card')

      await page.goto('/email/confirm')
      await expect(page.getByText('Bestätigungslink ist ungültig.')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.login-card')
    })

    test('initial setup screen stays within the viewport', async ({ page }) => {
      await mockInitialSetupApi(page)
      await page.goto('/setup')
      await expect(page.getByRole('button', { name: 'Admin mit Passkey erstellen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.login-card')
    })

    test('workspace shell and tenant home remain usable with long memory names', async ({ page }) => {
      await mockAuthenticatedOwnerApi(page, {
        folderMemories: [longNameMemory, weddingMemory],
        favoriteMemories: [longNameMemory],
        scopeRoots: [personalScopeRoot, groupScopeRoot, ...overflowGroupScopeRoots]
      })

      await page.goto('/')
      await expect(page.locator('.app-shell')).toBeVisible()
      await expect(page.getByRole('button', { name: 'Memory erstellen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.workspace-topbar')
      await assertElementFitsViewport(page, '.tenant-sidebar')
      if (viewport.width <= 1020) {
        await assertElementTop(page, '.tenant-sidebar', 1)
        await assertElementHeightAtMost(page, '.tenant-sidebar', 96)
        if (viewport.width > 720) {
          await assertElementHeightAtMost(page, '.workspace-topbar', 60)
          await assertElementHeightAtMost(page, '.app-compact-header', 128)
        }
        await expect(
          page.locator('.tenant-sidebar').getByRole('button', { name: 'Bereich erstellen' })
        ).toBeVisible()
        await expect(
          page.locator('.tenant-sidebar').getByRole('link', { name: 'Gruppe erstellen' })
        ).toHaveCount(0)
        await expect(page.locator('.app-breadcrumb')).toBeVisible()
        await page.locator('.workspace-topbar [data-testid="workspace-search-trigger"]').click()
        await expect(page.locator('.workspace-search-panel')).toBeVisible()
        await expect(page.locator('.tenant-sidebar .sidebar-brand')).toBeVisible()
        await page.keyboard.press('Escape')

        if (viewport.width <= 720) {
          await expect(page.locator('.tenant-sidebar .compact-tenant-menu-button')).toBeHidden()
          await expect(page.locator('.tenant-sidebar .compact-group-divider')).toBeHidden()
          await expect(page.locator('.tenant-sidebar .sidebar-personal-link')).toBeHidden()
          await expect(page.locator('.tenant-sidebar .sidebar-nav-link:visible')).toHaveCount(0)
          await page.locator('.tenant-sidebar').getByRole('button', { name: 'Navigationsmenü öffnen' }).click()
          await expect(page.getByRole('dialog', { name: 'Navigation' })).toBeVisible()
          await expect(page.getByRole('link', { name: /Persönlich/ })).toBeVisible()
          await expect(page.getByRole('link', { name: /Group With A Very Long Name/ })).toBeVisible()
          await expect(page.getByRole('link', { name: /Sommerreise mit einem/ })).toBeVisible()
          await expect(page.getByRole('button', { name: /Gruppe erstellen/ })).toBeVisible()
          await expect(page.getByRole('dialog', { name: 'Navigation' }).getByRole('button', { name: 'Bereich erstellen' })).toBeVisible()
        } else {
          await expect(page.locator('.tenant-sidebar').getByRole('button', { name: 'Bereich erstellen' })).toBeVisible()
          await expect(page.locator('.tenant-sidebar').getByRole('button', { name: 'Bereichsmenü öffnen' })).toHaveCount(0)
          await expect(page.locator('.tenant-sidebar .compact-group-divider')).toBeVisible()
          await page.locator('.tenant-sidebar').getByRole('button', { name: 'Gruppenmenü öffnen' }).click()
          await expect(page.getByRole('menuitem', { name: 'Gruppe erstellen' })).toBeVisible()
          await expect(
            page.locator('.tenant-sidebar .compact-group-links .compact-group-link').first()
          ).toBeVisible()
        }
      }
    })

    test('memory detail actions, tabs, and share dialog adapt to the viewport', async ({ page }) => {
      await mockMemoryDetailApi(page, { memory: longNameMemory })

      await page.goto(longNameMemory.path)
      await expect(page.getByRole('heading', { name: longNameMemory.displayName })).toBeVisible()
      await expect(page.getByRole('tab', { name: 'Galerie' })).toBeVisible()
      await expect(page.locator('.memory-detail-actions').getByRole('button', { name: 'Hochladen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.memory-detail-actions')

      const shareButton = page.locator('.memory-detail-actions').getByRole('button', { name: 'Memory teilen' })
      if (await shareButton.isVisible()) {
        await shareButton.click()
      } else {
        await page.locator('.memory-detail-actions').getByRole('button', { name: 'Mehr' }).click()
        await page.getByRole('menuitem', { name: 'Memory teilen' }).click()
      }
      await expect(page.getByRole('dialog', { name: 'Memory teilen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.p-dialog')
    })

    test('settings screens keep management rows and forms responsive', async ({ page }) => {
      await mockResponsiveSettingsApi(page)

      await page.goto('/~me/settings')
      await expect(page.getByRole('heading', { name: 'Meine Einstellungen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)

      await page.goto('/settings')
      await expect(page.getByRole('heading', { name: 'Friends' })).toBeVisible()
      await expect(page.getByRole('heading', { name: 'Gruppen' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.settings-page')

      await page.getByRole('button', { name: 'User einladen' }).click()
      await expect(page.getByRole('dialog', { name: 'Einladen' })).toBeVisible()
      await assertElementFitsViewport(page, '.p-dialog')
    })

    test('public share view is responsive without the workspace shell', async ({ page }) => {
      await mockPublicViewerShareApi(page, {
        media: [geotaggedMedia],
        tracks: [publicTrack],
        playlists: [publicPlaylist],
        journalMarkdown: `::playlist:${publicPlaylist.id} Playlist\n\n::track:${publicTrack.id} Track`
      })

      await page.goto('/s/shr-e2e')
      await expect(page.locator('.app-shell')).toHaveCount(0)
      await expect(page.getByRole('heading', { name: weddingMemory.displayName })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.public-share-view')

      await page.getByRole('tab', { name: 'Karte' }).click()
      await expect(page.getByRole('tabpanel', { name: 'Karte' }).getByText('Externe Kartenkacheln sind deaktiviert')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.memory-context-layout')
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.memory-context-sidebar')

      await page.getByRole('tab', { name: 'Playlists' }).click()
      await expect(page.getByRole('heading', { name: 'Roadtrip Soundtrack' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await assertElementFitsViewport(page, '.memory-playlist-grid')

      await page.getByRole('button', { name: 'Datenschutz' }).click()
      await expect(page.getByRole('dialog', { name: 'Externe Inhalte' })).toBeVisible()
      await assertElementFitsViewport(page, '.p-dialog')
    })
  })
}

async function mockAnonymousSetupCompletedApi(page: Page) {
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

    if (method === 'PUT' && url.pathname === `/api/users/${adminUser.userId}/password`) {
      await fulfillJson(route, {})
      return
    }

    await fulfillUnhandled(route, method, url.pathname)
  })
}

async function assertNoHorizontalOverflow(page: Page) {
  await expect
    .poll(async () =>
      page.evaluate(() => {
        const doc = document.documentElement
        return Math.ceil(doc.scrollWidth - doc.clientWidth)
      })
    )
    .toBeLessThanOrEqual(1)
}

async function assertElementTop(page: Page, selector: string, expectedTop: number) {
  const top = await page.locator(selector).first().evaluate((element) => {
    return Math.floor(element.getBoundingClientRect().top)
  })

  expect(top).toBeLessThanOrEqual(expectedTop)
}

async function assertElementHeightAtMost(page: Page, selector: string, expectedHeight: number) {
  const height = await page.locator(selector).first().evaluate((element) => {
    return Math.ceil(element.getBoundingClientRect().height)
  })

  expect(height).toBeLessThanOrEqual(expectedHeight)
}

async function assertElementFitsViewport(page: Page, selector: string) {
  const overflow = await page.locator(selector).first().evaluate((element) => {
    const rect = element.getBoundingClientRect()
    return {
      left: Math.floor(rect.left),
      right: Math.ceil(rect.right),
      width: window.innerWidth
    }
  })

  expect(overflow.left).toBeGreaterThanOrEqual(0)
  expect(overflow.right).toBeLessThanOrEqual(overflow.width + 1)
}
